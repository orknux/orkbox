package io.mszymanski.orknux.orkbox

import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.Edit
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.runtime.Signal
import io.mszymanski.orknux.orkbox.session.Execution
import io.mszymanski.orknux.orkbox.session.ExecutionStatus
import io.mszymanski.orknux.orkbox.session.Session
import io.mszymanski.orknux.orkbox.session.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behaviour every runtime must share. Subclasses say how to reach the
 * runtime and how to phrase a few commands in its shell.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SessionContract {
    abstract fun config(): Config
    abstract fun createRequest(): CreateSessionRequest

    /** argv running [script] in the runtime's shell. */
    abstract fun shellArgv(script: String): List<String>
    abstract val printEnvCommand: String
    abstract val sleepCommand: String
    abstract val catCommand: String

    lateinit var orkbox: Orkbox
    lateinit var session: Session

    @BeforeAll
    fun setUp() = runBlocking {
        orkbox = Orkbox(config())
        session = orkbox.sessions.create(createRequest())
        assertEquals(SessionStatus.READY, session.status, session.failure)
    }

    @AfterAll
    fun tearDown() {
        if (::orkbox.isInitialized) orkbox.close()
    }

    private suspend fun run(request: ExecuteRequest): Execution {
        val execution = orkbox.sessions.execute(session, request)
        assertTrue(orkbox.sessions.await(session, execution, 120), "execution did not finish")
        return execution
    }

    @Test
    fun `files can be written, read, edited, moved and deleted`() = runBlocking {
        val files = orkbox.files
        val written = files.write(session, "src/hello.txt", "one\ntwo\nthree\n".toByteArray(), null, null)
        assertEquals("src/hello.txt", written.path)

        val content = files.read(session, "src/hello.txt", null, null)
        assertEquals("one\ntwo\nthree\n", String(content.bytes))
        assertEquals(3, content.lineCount)
        assertEquals(written.etag, content.etag)
        assertEquals("two\n", String(files.read(session, "src/hello.txt", 2, 2).bytes))

        val stale = assertFailsWith<ApiException> { files.write(session, "src/hello.txt", "x".toByteArray(), null, "\"nope\"") }
        assertEquals(412, stale.status)

        val edited = files.edit(session, EditRequest("src/hello.txt", listOf(Edit("two", "2"), Edit("e", "E", replaceAll = true))))
        assertEquals(listOf(1, 3), edited.replacements)
        assertEquals("onE\n2\nthrEE\n", String(files.read(session, "src/hello.txt", null, null).bytes))
        assertEquals("edit_ambiguous", assertFailsWith<ApiException> {
            files.edit(session, EditRequest("src/hello.txt", listOf(Edit("E", "e"))))
        }.code)
        assertEquals("edit_no_match", assertFailsWith<ApiException> {
            files.edit(session, EditRequest("src/hello.txt", listOf(Edit("missing", "x"))))
        }.code)

        val entry = files.stat(session, "src/hello.txt")
        assertEquals("file", entry.type)
        assertEquals(12, entry.size)

        files.move(session, MoveRequest("src/hello.txt", "docs/hello.txt"))
        assertEquals("file_not_found", assertFailsWith<ApiException> { files.stat(session, "src/hello.txt") }.code)
        files.write(session, "docs/other.txt", "x".toByteArray(), null, null)
        assertEquals("file_exists", assertFailsWith<ApiException> {
            files.move(session, MoveRequest("docs/other.txt", "docs/hello.txt"))
        }.code)
        files.move(session, MoveRequest("docs/other.txt", "docs/hello.txt", overwrite = true))
        assertEquals("x", String(files.read(session, "docs/hello.txt", null, null).bytes))

        assertEquals("directory_not_empty", assertFailsWith<ApiException> { files.delete(session, "docs", false) }.code)
        files.delete(session, "docs", true)
        assertEquals("file_not_found", assertFailsWith<ApiException> { files.stat(session, "docs") }.code)
    }

    @Test
    fun `listing honours depth, hidden files and globs`() = runBlocking {
        val files = orkbox.files
        files.write(session, "list/a.kt", "a".toByteArray(), null, null)
        files.write(session, "list/sub/b.kt", "b".toByteArray(), null, null)
        files.write(session, "list/sub/c.txt", "c".toByteArray(), null, null)
        files.write(session, "list/.hidden", "h".toByteArray(), null, null)

        val top = files.list(session, "list", null, null, includeHidden = false, ignoreGitignore = false, limit = 100)
        assertEquals(listOf("list/a.kt", "list/sub"), top.entries.map { it.path })
        assertEquals("directory", top.entries[1].type)

        val all = files.list(session, "list", 5, null, includeHidden = true, ignoreGitignore = false, limit = 100)
        assertEquals(listOf("list/.hidden", "list/a.kt", "list/sub", "list/sub/b.kt", "list/sub/c.txt"), all.entries.map { it.path })

        val kotlin = files.list(session, "list", null, "**/*.kt", includeHidden = false, ignoreGitignore = false, limit = 100)
        assertEquals(listOf("list/a.kt", "list/sub/b.kt"), kotlin.entries.map { it.path })

        val limited = files.list(session, "list", 5, null, includeHidden = false, ignoreGitignore = false, limit = 2)
        assertTrue(limited.truncated)
    }

    @Test
    fun `search finds matches with context`() = runBlocking {
        val files = orkbox.files
        files.write(session, "search/a.txt", "alpha\nbeta\nTODO: fix\ngamma\n".toByteArray(), null, null)
        files.write(session, "search/b.md", "nothing here\n".toByteArray(), null, null)
        val result = files.search(session, SearchRequest(pattern = "todo", path = "search", caseSensitive = false, contextLines = 1))
        assertEquals(1, result.matches.size)
        val match = result.matches.single()
        assertEquals("search/a.txt", match.path)
        assertEquals(3, match.line)
        assertEquals(1, match.column)
        assertEquals("TODO: fix", match.text)
        val regex = files.search(session, SearchRequest(pattern = "^g.*a$", regex = true, path = "search"))
        assertEquals(listOf(4), regex.matches.map { it.line })
    }

    @Test
    fun `paths outside the workspace are rejected`() = runBlocking {
        val e = assertFailsWith<ApiException> { orkbox.files.read(session, "../../etc/passwd", null, null) }
        assertEquals("path_outside_workspace", e.code)
    }

    @Test
    fun `commands run with exit codes and env`() = runBlocking {
        val ok = run(ExecuteRequest(command = "echo hello"))
        assertEquals(ExecutionStatus.SUCCEEDED, ok.status)
        assertEquals("hello", ok.toDto(1000).stdout!!.trim())

        val failed = run(ExecuteRequest(command = "exit 3"))
        assertEquals(ExecutionStatus.FAILED, failed.status)
        assertEquals(3, failed.exitCode)

        val env = run(ExecuteRequest(command = printEnvCommand, env = mapOf("ORKBOX_TEST_VAR" to "from-request")))
        assertEquals("from-request", env.toDto(1000).stdout!!.trim())

        val argv = run(ExecuteRequest(argv = shellArgv("echo from-argv")))
        assertEquals("from-argv", argv.toDto(1000).stdout!!.trim())

        orkbox.files.write(session, "cwd/marker.txt", "m".toByteArray(), null, null)
        val listing = run(ExecuteRequest(command = if (isLocalWindows()) "dir /b" else "ls", cwd = "cwd"))
        assertContains(listing.toDto(1000).stdout!!, "marker.txt")
    }

    @Test
    fun `missing programs fail like a shell would`() = runBlocking {
        val missing = run(ExecuteRequest(argv = listOf("orkbox-no-such-program")))
        assertEquals(ExecutionStatus.FAILED, missing.status)
        assertTrue(missing.exitCode != 0)
    }

    @Test
    fun `timeouts kill the command`() = runBlocking {
        val execution = run(ExecuteRequest(command = sleepCommand, timeoutSeconds = 1))
        assertEquals(ExecutionStatus.TIMED_OUT, execution.status)
    }

    @Test
    fun `signals stop a running command`() = runBlocking {
        val execution = orkbox.sessions.execute(session, ExecuteRequest(command = sleepCommand))
        delay(1000)
        assertTrue(execution.isRunning)
        orkbox.sessions.signal(session, execution, Signal.TERM)
        assertTrue(orkbox.sessions.await(session, execution, 30))
        assertEquals(ExecutionStatus.KILLED, execution.status)
        assertEquals("TERM", execution.signal?.name)
    }

    @Test
    fun `interactive stdin is fed through the API`() = runBlocking {
        val execution = orkbox.sessions.execute(session, ExecuteRequest(command = catCommand, interactive = true))
        orkbox.sessions.writeStdin(session, execution, "piped-in\n".toByteArray(), close = true)
        assertTrue(orkbox.sessions.await(session, execution, 30))
        assertEquals(ExecutionStatus.SUCCEEDED, execution.status)
        assertContains(String(execution.stdout.read(0).bytes), "piped-in")

        val inline = run(ExecuteRequest(command = catCommand, stdin = "inline\n"))
        assertContains(inline.toDto(1000).stdout!!, "inline")
    }

    @Test
    fun `archives round-trip`() = runBlocking {
        val files = orkbox.files
        files.write(session, "arch/src/one.txt", "1".toByteArray(), null, null)
        files.write(session, "arch/two.txt", "2".toByteArray(), null, null)
        val tar = ByteArrayOutputStream()
        files.exportArchive(session, "arch", tar)
        assertTrue(tar.size() > 0)
        files.importArchive(session, "restored", tar.toByteArray().inputStream())
        assertEquals("1", String(files.read(session, "restored/src/one.txt", null, null).bytes))
        assertEquals("2", String(files.read(session, "restored/two.txt", null, null).bytes))
    }

    @Test
    fun `the log records what happened`() = runBlocking {
        run(ExecuteRequest(command = "echo logged"))
        val types = session.events.after(0, 10_000) { true }.map { it.type }.toSet()
        assertTrue("session.created" in types)
        assertTrue("session.ready" in types)
        assertTrue("execution.started" in types)
        assertTrue("execution.output" in types)
        assertTrue("execution.finished" in types)
        assertNotNull(session.toDto().workdir)
    }

    open fun isLocalWindows() = false
}
