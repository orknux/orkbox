package io.mszymanski.orknux.codebox.runtime

import io.mszymanski.orknux.codebox.ApiException
import io.mszymanski.orknux.codebox.session.FileService
import io.mszymanski.orknux.codebox.session.OutputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
