package io.mszymanski.orknux.orkbox.runtime

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.session.FileService
import io.mszymanski.orknux.orkbox.session.OutputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RipgrepArgsTest {
    @Test
    fun `a search from the workspace root names the root rather than reading stdin`() {
        assertEquals(".", Search.ripgrepArgs(SearchRequest(pattern = "needle"), "").last())
        assertEquals("src", Search.ripgrepArgs(SearchRequest(pattern = "needle"), "src").last())
    }
}

class LocalOwnershipTest {
    private fun spec(id: String) = SandboxSpec(id, null, emptyMap(), null, true, emptyList())

    @Test
    fun `start-up clean-up removes only the workspaces this instance made`() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("orkbox-owners")
        val mine = LocalRuntime(io.mszymanski.orknux.orkbox.LocalConfig(root = root, owner = "a"))
        val theirs = LocalRuntime(io.mszymanski.orknux.orkbox.LocalConfig(root = root, owner = "b"))
        mine.create(spec("ses_mine"))
        theirs.create(spec("ses_theirs"))
        java.nio.file.Files.createDirectories(root.resolve("ses_unmarked"))

        mine.cleanupOrphans()

        assertFalse(java.nio.file.Files.exists(root.resolve("ses_mine")))
        assertTrue(java.nio.file.Files.exists(root.resolve("ses_theirs")))
        assertTrue(java.nio.file.Files.exists(root.resolve("ses_unmarked")))
        deleteTree(root)
    }

    @Test
    fun `a workspace removed from under its session is reported missing, not outside`() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("orkbox-missing")
        val sandbox = LocalRuntime(io.mszymanski.orknux.orkbox.LocalConfig(root = root)).create(spec("ses_gone"))
        deleteTree(root.resolve("ses_gone"))
        val refused = assertFailsWith<ApiException> { sandbox.list("modules", 1, false) }
        assertEquals("workspace_missing", refused.code)
        deleteTree(root)
    }
}

class DeleteTreeOrSayTest {
    @Test
    fun `a tree deleted whole says nothing`() {
        val dir = java.nio.file.Files.createTempDirectory("orkbox-delete")
        java.nio.file.Files.writeString(dir.resolve("a.txt"), "a")
        deleteTreeOrSay(dir, "target")
        assertFalse(java.nio.file.Files.exists(dir))
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `a file held open on Windows is reported rather than passed over`() {
        val dir = java.nio.file.Files.createTempDirectory("orkbox-delete")
        val held = dir.resolve("app.jar")
        java.nio.file.Files.writeString(held, "jar")
        java.io.FileInputStream(held.toFile()).use {
            val refused = assertFailsWith<ApiException> { deleteTreeOrSay(dir, "target") }
            assertEquals("delete_incomplete", refused.code)
            assertTrue(refused.detail.orEmpty().contains("app.jar"))
        }
        deleteTree(dir)
    }
}

class WorkspacePathTest {
    @Test
    fun `relative paths are normalised`() {
        assertEquals("", WorkspacePath.normalize(null, "/workspace"))
        assertEquals("", WorkspacePath.normalize(".", "/workspace"))
        assertEquals("src/a.kt", WorkspacePath.normalize("./src//a.kt", "/workspace"))
        assertEquals("a.kt", WorkspacePath.normalize("src/../a.kt", "/workspace"))
        assertEquals("src/a.kt", WorkspacePath.normalize("src\\a.kt", "/workspace"))
    }

    @Test
    fun `absolute paths must be inside the workspace`() {
        assertEquals("src", WorkspacePath.normalize("/workspace/src", "/workspace"))
        assertEquals("", WorkspacePath.normalize("/workspace", "/workspace"))
        assertEquals("x", WorkspacePath.normalize("c:\\Temp\\ses_1\\x", "C:\\Temp\\ses_1", caseInsensitive = true))
        assertFailsWith<ApiException> { WorkspacePath.normalize("/workspace2/a", "/workspace") }
        assertFailsWith<ApiException> { WorkspacePath.normalize("/etc/passwd", "/workspace") }
    }

    @Test
    fun `climbing out is rejected`() {
        val e = assertFailsWith<ApiException> { WorkspacePath.normalize("../x", "/workspace") }
        assertEquals("path_outside_workspace", e.code)
        assertFailsWith<ApiException> { WorkspacePath.normalize("a/../../x", "/workspace") }
    }
}

class WindowsQuotingTest {
    @Test
    fun `arguments are quoted for CommandLineToArgvW`() {
        assertEquals("plain", Processes.quoteWindowsArg("plain"))
        assertEquals("\"\"", Processes.quoteWindowsArg(""))
        assertEquals("\"a b\"", Processes.quoteWindowsArg("a b"))
        assertEquals("\"say \\\"hi\\\"\"", Processes.quoteWindowsArg("say \"hi\""))
        // Backslashes only double where they precede a quote.
        assertEquals("\"dir\\ x\\\\\"", Processes.quoteWindowsArg("dir\\ x\\"))
        assertEquals(listOf("cmd.exe", "/c", "echo \"x\""), Processes.windowsArgv(listOf("cmd.exe", "/c", "echo \"x\"")))
    }
}

class GlobTest {
    @Test
    fun `globs follow gitignore conventions`() {
        assertTrue(Glob("*.kt").matches("src/main/App.kt"))
        assertFalse(Glob("src/*.kt").matches("src/main/App.kt"))
        assertTrue(Glob("src/**/*.kt").matches("src/main/App.kt"))
        assertTrue(Glob("src/**/*.kt").matches("src/App.kt"))
        assertTrue(Glob("**/*.{kt,kts}").matches("build.gradle.kts"))
        assertTrue(Glob("file?.[ch]").matches("file1.c"))
        assertFalse(Glob("file?.[!ch]").matches("file1.c"))
    }
}

class OutputBufferTest {
    @Test
    fun `offsets survive dropping old output`() {
        val buffer = OutputBuffer(16)
        buffer.append("0123456789".toByteArray())
        buffer.append("abcdefghij".toByteArray())
        assertEquals(20, buffer.total.value)
        assertTrue(buffer.retainedFrom > 0)
        val chunk = buffer.read(0)
        assertEquals(buffer.retainedFrom, chunk.offset)
        assertTrue("0123456789abcdefghij".endsWith(String(chunk.bytes)))
        assertEquals("hij", String(buffer.read(17).bytes))
        assertEquals(20, buffer.read(17).next)
    }

    @Test
    fun `excerpt keeps head and tail`() {
        val buffer = OutputBuffer(1024)
        buffer.append(("a".repeat(50) + "b".repeat(50)).toByteArray())
        val excerpt = buffer.excerpt(20)
        assertTrue(excerpt.truncated)
        assertTrue(excerpt.text.startsWith("aaaaaaaaaa"))
        assertTrue(excerpt.text.endsWith("bbbbbbbbbb"))
        assertFalse(buffer.excerpt(1000).truncated)
    }

    @Test
    fun `huge single write keeps the tail`() {
        val buffer = OutputBuffer(8)
        buffer.append("0123456789ABCDEF".toByteArray())
        assertEquals("89ABCDEF", String(buffer.read(0).bytes))
        assertEquals(8, buffer.retainedFrom)
    }
}

class FileServiceHelpersTest {
    @Test
    fun `lines keep terminators`() {
        assertEquals(listOf("a\n", "b\r\n", "c"), FileService.splitLines("a\nb\r\nc"))
        assertEquals(emptyList(), FileService.splitLines(""))
    }

    @Test
    fun `utf8 detection`() {
        assertTrue(FileService.isUtf8("zażółć".toByteArray()))
        assertFalse(FileService.isUtf8(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        assertFalse(FileService.isUtf8(byteArrayOf(65, 0, 66)))
    }
}
