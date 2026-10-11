package io.mszymanski.orknux.orkbox

import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.GitSource
import io.mszymanski.orknux.orkbox.model.Source
import io.mszymanski.orknux.orkbox.runtime.DockerRuntime
import io.mszymanski.orknux.orkbox.runtime.Processes
import io.mszymanski.orknux.orkbox.session.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSessionTest : SessionContract() {
    private val root: Path = Files.createTempDirectory("orkbox-local-test")
    private val windows = LocalConfig.isWindows

    override fun config() = Config(
        runtimes = setOf(Config.RUNTIME_LOCAL),
        defaultRuntime = Config.RUNTIME_LOCAL,
        killGraceSeconds = 2,
        local = LocalConfig(root = root),
    )

    override fun createRequest() = CreateSessionRequest()
    override fun isLocalWindows() = windows

    override fun shellArgv(script: String) = if (windows) listOf("cmd.exe", "/d", "/s", "/c", script) else listOf("/bin/sh", "-c", script)
    override val printEnvCommand = if (windows) "echo %ORKBOX_TEST_VAR%" else "echo \"\$ORKBOX_TEST_VAR\""
    override val sleepCommand = if (windows) "ping -n 60 127.0.0.1 >nul" else "sleep 60"
    override val catCommand = if (windows) "sort" else "cat"

    /** A local session's port is this machine's: the forward reaches it directly. */
    @Test
    fun `a forwarded port carries a connection to what listens on it`() = runBlocking {
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        Thread.ofVirtual().start {
            server.accept().use { it.getOutputStream().write("hello from the session".toByteArray()) }
        }
        val forward = orkbox.sessions.forward(session, server.localPort)
        assertEquals(listOf(forward), session.toDto().ports)
        val read = java.net.Socket("127.0.0.1", forward.hostPort).use { String(it.getInputStream().readAllBytes()) }
        assertEquals("hello from the session", read)
        orkbox.sessions.unforward(session, server.localPort)
        server.close()
    }

    @Test
    fun `workspace is removed when the session stops`() = runBlocking {
        val other = orkbox.sessions.create(CreateSessionRequest())
        val dir = Path.of(other.sandbox!!.workdir)
        assertEquals(true, dir.exists())
        orkbox.sessions.stop(other, "deleted")
        assertEquals(SessionStatus.STOPPED, other.status)
        assertFalse(dir.exists())
    }

    @Test
    fun `sessions can start from a git repository`(@TempDir repo: Path) = runBlocking {
        assumeTrue(runCatching { Processes.run(listOf("git", "--version")).ok }.getOrDefault(false), "git not installed")
        suspend fun git(vararg args: String) {
            val r = Processes.run(listOf("git", "-C", repo.toString()) + args)
            assertEquals(0, r.exitCode, r.err)
        }
        git("init", "--quiet", "--initial-branch=main")
        Files.writeString(repo.resolve("README.md"), "from git\n")
        git("add", ".")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "init")

        val cloned = orkbox.sessions.create(CreateSessionRequest(source = Source(GitSource(url = repo.toUri().toString(), ref = "main", depth = 1))))
        assertEquals(SessionStatus.READY, cloned.status, cloned.failure)
        assertEquals("from git\n", String(orkbox.files.read(cloned, "README.md", null, null).bytes).replace("\r\n", "\n"))
        orkbox.sessions.stop(cloned, "deleted")

        val broken = orkbox.sessions.create(CreateSessionRequest(source = Source(GitSource(url = repo.resolve("missing").toUri().toString()))))
        assertEquals(SessionStatus.FAILED, broken.status)
        assertContains(broken.failure!!, "git clone failed")
    }
}

/**
 * Runs the contract against real containers. Skipped when no Docker daemon
 * is reachable. The image defaults to a plain Debian (no git, no ripgrep, so
 * the fallbacks get exercised); `ORKBOX_TEST_IMAGE` picks another.
 */
class DockerSessionTest : SessionContract() {
    private val image = System.getenv("ORKBOX_TEST_IMAGE") ?: "debian:bookworm-slim"

    override fun config() = Config(
        runtimes = setOf(Config.RUNTIME_DOCKER),
        defaultRuntime = Config.RUNTIME_DOCKER,
        killGraceSeconds = 2,
        docker = DockerConfig(defaultImage = image, cleanupOnStart = false),
    )

    override fun createRequest(): CreateSessionRequest {
        val reason = runBlocking { DockerRuntime(DockerConfig()).unavailableReason() }
        assumeTrue(reason == null, "Docker not available: $reason")
        return CreateSessionRequest(labels = mapOf("test" to "DockerSessionTest"))
    }

    override fun shellArgv(script: String) = listOf("sh", "-c", script)
    override val printEnvCommand = "echo \"\$ORKBOX_TEST_VAR\""
    override val sleepCommand = "sleep 60"
    override val catCommand = "cat"

    /**
     * A port forwarded on a running container carries a request from the host
     * to what a command serves inside it - through docker exec, which works on
     * Docker Desktop where the container's address does not - and closing it,
     * or stopping the session, stops that.
     */
    @Test
    fun `a forwarded port reaches a server in a running container`() = runBlocking {
        val served = orkbox.sessions.create(createRequest().copy(image = "python:3.12-slim"))
        assertEquals(SessionStatus.READY, served.status, served.failure)
        try {
            orkbox.files.write(served, "index.html", "served from the box".toByteArray(), null, null)
            orkbox.sessions.execute(served, ExecuteRequest(command = "python3 -m http.server 8000", wait = false))
            val port = orkbox.sessions.forward(served, 8000)
            assertEquals(8000, port.container)
            assertEquals(listOf(port), served.toDto().ports)
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port.hostPort}/index.html")).build()
            var body: String? = null
            repeat(80) {
                if (body == null) {
                    body = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                    if (body == null) delay(250)
                }
            }
            assertEquals("served from the box", body)
            orkbox.sessions.unforward(served, 8000)
            assertTrue(runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.isFailure, "a closed forward answers nothing")
            val again = orkbox.sessions.forward(served, 8000)
            orkbox.sessions.stop(served, "deleted")
            assertTrue(runCatching { java.net.Socket("127.0.0.1", again.hostPort).close() }.isFailure, "stopping the session closes its forwards")
        } finally {
            if (served.isActive) orkbox.sessions.stop(served, "deleted")
        }
    }

    @Test
    fun `usage is read from the container cgroup`() = runBlocking {
        val usage = session.sandbox!!.usage()
        println("usage: $usage")
        assertEquals(true, usage != null)
    }
}
