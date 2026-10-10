package io.mszymanski.orknux.orktin

import io.mszymanski.orknux.orktin.model.CreateSessionRequest
import io.mszymanski.orknux.orktin.model.GitSource
import io.mszymanski.orknux.orktin.model.Source
import io.mszymanski.orknux.orktin.runtime.DockerRuntime
import io.mszymanski.orknux.orktin.runtime.Processes
import io.mszymanski.orknux.orktin.session.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalSessionTest : SessionContract() {
    private val root: Path = Files.createTempDirectory("orktin-local-test")
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
    override val printEnvCommand = if (windows) "echo %ORKTIN_TEST_VAR%" else "echo \"\$ORKTIN_TEST_VAR\""
    override val sleepCommand = if (windows) "ping -n 60 127.0.0.1 >nul" else "sleep 60"
    override val catCommand = if (windows) "sort" else "cat"

    @Test
    fun `workspace is removed when the session stops`() = runBlocking {
        val other = orktin.sessions.create(CreateSessionRequest())
        val dir = Path.of(other.sandbox!!.workdir)
        assertEquals(true, dir.exists())
        orktin.sessions.stop(other, "deleted")
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

        val cloned = orktin.sessions.create(CreateSessionRequest(source = Source(GitSource(url = repo.toUri().toString(), ref = "main", depth = 1))))
        assertEquals(SessionStatus.READY, cloned.status, cloned.failure)
        assertEquals("from git\n", String(orktin.files.read(cloned, "README.md", null, null).bytes).replace("\r\n", "\n"))
        orktin.sessions.stop(cloned, "deleted")

        val broken = orktin.sessions.create(CreateSessionRequest(source = Source(GitSource(url = repo.resolve("missing").toUri().toString()))))
        assertEquals(SessionStatus.FAILED, broken.status)
        assertContains(broken.failure!!, "git clone failed")
    }
}

/**
 * Runs the contract against real containers. Skipped when no Docker daemon
 * is reachable. The image defaults to a plain Debian (no git, no ripgrep, so
 * the fallbacks get exercised); `ORKTIN_TEST_IMAGE` picks another.
 */
class DockerSessionTest : SessionContract() {
    private val image = System.getenv("ORKTIN_TEST_IMAGE") ?: "debian:bookworm-slim"

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
    override val printEnvCommand = "echo \"\$ORKTIN_TEST_VAR\""
    override val sleepCommand = "sleep 60"
    override val catCommand = "cat"

    @Test
    fun `usage is read from the container cgroup`() = runBlocking {
        val usage = session.sandbox!!.usage()
        println("usage: $usage")
        assertEquals(true, usage != null)
    }
}
