package io.mszymanski.orknux.orktin.session

import io.mszymanski.orknux.orktin.ApiException
import io.mszymanski.orknux.orktin.Config
import io.mszymanski.orknux.orktin.model.DirectoryListing
import io.mszymanski.orknux.orktin.model.EditRequest
import io.mszymanski.orknux.orktin.model.EditResult
import io.mszymanski.orknux.orktin.model.FileEntry
import io.mszymanski.orknux.orktin.model.MoveRequest
import io.mszymanski.orknux.orktin.model.SearchRequest
import io.mszymanski.orknux.orktin.model.SearchResult
import io.mszymanski.orknux.orktin.model.WriteResult
import io.mszymanski.orknux.orktin.runtime.Glob
import io.mszymanski.orknux.orktin.runtime.ProgramNotFoundException
import io.mszymanski.orknux.orktin.runtime.Sandbox
import io.mszymanski.orknux.orktin.runtime.WorkspacePath
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** The file side of the Session API, on top of a session's [Sandbox]. */
class FileService {
    class Content(val bytes: ByteArray, val text: Boolean, val etag: String, val size: Int, val lineCount: Int?)

    private fun resolve(session: Session, sandbox: Sandbox, path: String?) =
        WorkspacePath.normalize(path, sandbox.workdir, caseInsensitive = session.runtime == Config.RUNTIME_LOCAL)

    suspend fun read(session: Session, path: String, startLine: Int?, endLine: Int?): Content {
        val sandbox = session.requireReady()
        val bytes = sandbox.readFile(resolve(session, sandbox, path))
        val text = isUtf8(bytes)
        val etag = etag(bytes)
        if (!text) {
            if (startLine != null || endLine != null) throw ApiException.badRequest("not_text", "$path is not UTF-8 text; line ranges need text")
            return Content(bytes, false, etag, bytes.size, null)
        }
        val lines = splitLines(String(bytes, Charsets.UTF_8))
        if (startLine == null && endLine == null) return Content(bytes, true, etag, bytes.size, lines.size)
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: lines.size).coerceAtMost(lines.size)
        val slice = if (from > to) "" else lines.subList(from - 1, to).joinToString("")
        return Content(slice.toByteArray(), true, etag, bytes.size, lines.size)
    }

    suspend fun write(session: Session, path: String, content: ByteArray, mode: String?, ifMatch: String?): WriteResult {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, path)
        if (mode != null && !Regex("[0-7]{3,4}").matches(mode)) throw ApiException.badRequest("invalid_mode", "mode must be octal, e.g. 644")
        if (ifMatch != null) {
            val current = runCatching { etag(sandbox.readFile(relative)) }.getOrNull()
            if (current != ifMatch) throw ApiException(412, "etag_mismatch", "Precondition failed", "$path changed since it was read")
        }
        sandbox.writeFile(relative, content, mode)
        session.events.append("file.written", buildJsonObject { put("path", relative); put("bytes", content.size) })
        return WriteResult(relative, content.size.toLong(), etag(content))
    }

    suspend fun edit(session: Session, request: EditRequest): EditResult {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, request.path)
        if (request.edits.isEmpty()) throw ApiException.badRequest("invalid_edit", "edits must not be empty")
        val bytes = sandbox.readFile(relative)
        if (!isUtf8(bytes)) throw ApiException.badRequest("not_text", "${request.path} is not UTF-8 text")
        var text = String(bytes, Charsets.UTF_8)
        val counts = ArrayList<Int>()
        for ((index, edit) in request.edits.withIndex()) {
            if (edit.oldText.isEmpty()) throw edit(index, "edit_no_match", "oldText must not be empty")
            val count = occurrences(text, edit.oldText)
            when {
                count == 0 -> throw edit(index, "edit_no_match", "Edit $index: oldText not found in ${request.path}")
                count > 1 && !edit.replaceAll -> throw edit(index, "edit_ambiguous",
                    "Edit $index: oldText occurs $count times in ${request.path}; make it unique or set replaceAll")
            }
            text = if (edit.replaceAll) text.replace(edit.oldText, edit.newText) else text.replaceFirst(edit.oldText, edit.newText)
            counts += count
        }
        val updated = text.toByteArray()
        sandbox.writeFile(relative, updated, null)
        session.events.append("file.edited", buildJsonObject {
            put("path", relative)
            putJsonArray("replacements") { counts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        })
        return EditResult(relative, updated.size.toLong(), etag(updated), counts)
    }

    private fun edit(index: Int, code: String, detail: String) =
        ApiException(422, code, "Edit failed", detail, mapOf("editIndex" to index.toString()))

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var at = text.indexOf(needle)
        while (at >= 0) { count++; at = text.indexOf(needle, at + needle.length) }
        return count
    }

    suspend fun stat(session: Session, path: String): FileEntry {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, path)
        return sandbox.stat(relative) ?: throw ApiException.fileNotFound(path)
    }

    suspend fun list(
        session: Session,
        path: String?,
        depth: Int?,
        glob: String?,
        includeHidden: Boolean,
        ignoreGitignore: Boolean,
        limit: Int,
    ): DirectoryListing {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, path)
        // A glob is usually meant across the tree, so it lifts the default depth of 1.
        val effectiveDepth = depth ?: if (glob != null) null else 1
        var entries = sandbox.list(relative, effectiveDepth, includeHidden)
        if (!ignoreGitignore) {
            val ignored = ignoredPaths(sandbox, entries.map { it.path })
            if (ignored.isNotEmpty()) {
                entries = entries.filter { e -> generateSequence(e.path) { p -> WorkspacePath.parent(p).takeIf { it.isNotEmpty() } }.none { it in ignored } }
            }
        }
        if (glob != null) {
            val matcher = Glob(glob)
            entries = entries.filter { matcher.matches(it.path.removePrefix(relative).removePrefix("/")) || matcher.matches(it.path) }
        }
        return DirectoryListing(relative, entries.take(limit), entries.size > limit)
    }

    /** Paths git would ignore, or none when git or a repository is missing. */
    private suspend fun ignoredPaths(sandbox: Sandbox, paths: List<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val input = paths.joinToString("\u0000", postfix = "\u0000").toByteArray()
        val result = try {
            sandbox.run(listOf("git", "check-ignore", "--stdin", "-z"), input)
        } catch (_: ProgramNotFoundException) {
            return emptySet()
        }
        if (result.exitCode != 0) return emptySet()
        return result.out.split('\u0000').filter { it.isNotEmpty() }.map { it.trimEnd('/') }.toSet()
    }

    suspend fun delete(session: Session, path: String, recursive: Boolean) {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, path)
        sandbox.delete(relative, recursive)
        session.events.append("file.deleted", buildJsonObject { put("path", relative); put("recursive", recursive) })
    }

    suspend fun move(session: Session, request: MoveRequest) {
        val sandbox = session.requireReady()
        val from = resolve(session, sandbox, request.from)
        val to = resolve(session, sandbox, request.to)
        sandbox.move(from, to, request.overwrite)
        session.events.append("file.moved", buildJsonObject { put("from", from); put("to", to) })
    }

    suspend fun search(session: Session, request: SearchRequest): SearchResult {
        val sandbox = session.requireReady()
        if (request.pattern.isEmpty()) throw ApiException.badRequest("invalid_pattern", "pattern must not be empty")
        if (request.regex) runCatching { Regex(request.pattern) }.onFailure {
            throw ApiException.badRequest("invalid_pattern", "Invalid regex: ${it.message}")
        }
        val request = request.copy(
            maxResults = request.maxResults.coerceIn(1, 10_000),
            contextLines = request.contextLines.coerceIn(0, 20),
        )
        return sandbox.search(request, resolve(session, sandbox, request.path))
    }

    suspend fun exportArchive(session: Session, path: String?, out: OutputStream) {
        val sandbox = session.requireReady()
        sandbox.exportArchive(resolve(session, sandbox, path), out)
    }

    suspend fun importArchive(session: Session, path: String?, input: InputStream) {
        val sandbox = session.requireReady()
        val relative = resolve(session, sandbox, path)
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        sandbox.importArchive(relative, buffered, gzip)
        session.events.append("archive.uploaded", buildJsonObject { put("path", relative) })
    }

    companion object {
        fun etag(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
        }

        fun isUtf8(bytes: ByteArray): Boolean = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            !bytes.contains(0.toByte())
        } catch (_: CharacterCodingException) {
            false
        }

        /** Lines with their terminators kept, so a range joins back byte-exact. */
        fun splitLines(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val lines = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                val nl = text.indexOf('\n', start)
                val end = if (nl < 0) text.length else nl + 1
                lines += text.substring(start, end)
                start = end
            }
            return lines
        }
    }
}
