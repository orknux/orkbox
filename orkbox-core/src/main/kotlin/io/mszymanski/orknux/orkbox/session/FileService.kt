package io.mszymanski.orknux.orkbox.session

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.model.DirectoryListing
import io.mszymanski.orknux.orkbox.model.Edit
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.EditResult
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.WriteResult
import io.mszymanski.orknux.orkbox.runtime.Glob
import io.mszymanski.orknux.orkbox.runtime.ProgramNotFoundException
import io.mszymanski.orknux.orkbox.runtime.Sandbox
import io.mszymanski.orknux.orkbox.runtime.WorkspacePath
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
            if (edit.startLine != null) {
                text = byLines(text, edit, index, request.path)
                counts += 1
                continue
            }
            val asked = edit.oldText
            if (asked.isNullOrEmpty()) throw edit(index, "edit_no_match", "oldText must not be empty")
            if (edit.regex) {
                val pattern = runCatching { Regex(asked, RegexOption.MULTILINE) }.getOrElse {
                    throw edit(index, "invalid_regex", "Edit $index: oldText is not a valid regular expression: ${it.message}")
                }
                val found = pattern.findAll(text).count()
                when {
                    found == 0 -> throw edit(index, "edit_no_match", "Edit $index: the pattern matches nothing in ${request.path}")
                    found > 1 && !edit.replaceAll -> throw edit(index, "edit_ambiguous",
                        "Edit $index: the pattern matches $found times in ${request.path}; make it unique or set replaceAll")
                }
                text = if (edit.replaceAll) pattern.replace(text, edit.newText) else pattern.replaceFirst(text, edit.newText)
                counts += found
                continue
            }
            /*
             * Line endings are not the edit. A file with CRLF lines is matched by
             * text written with LF ones - which is how a caller writes it - and the
             * replacement takes the file's endings, so an edit neither fails on
             * them nor leaves a file with two kinds.
             */
            var old: String = asked
            var new = edit.newText
            if (occurrences(text, old) == 0 && text.contains("\r\n")) {
                val crlf = { s: String -> s.replace("\r\n", "\n").replace("\n", "\r\n") }
                old = crlf(old)
                new = crlf(new)
            }
            val count = occurrences(text, old)
            when {
                count == 0 -> throw edit(index, "edit_no_match", "Edit $index: oldText not found in ${request.path}")
                count > 1 && !edit.replaceAll -> throw edit(index, "edit_ambiguous",
                    "Edit $index: oldText occurs $count times in ${request.path}; make it unique or set replaceAll")
            }
            text = if (edit.replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
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

    /**
     * Lines [Edit.startLine]..[Edit.endLine] replaced by [Edit.newText], which is
     * how a caller holding line numbers from a read or a search edits without
     * copying the text back exactly. An [Edit.oldText] given with them is a
     * guard: the lines must hold it, or the file changed since they were read.
     * The file's own line endings are kept.
     */
    private fun byLines(text: String, edit: Edit, index: Int, path: String): String {
        val crlf = text.contains("\r\n")
        val eol = if (crlf) "\r\n" else "\n"
        val lines = text.split(eol).toMutableList()
        val endsWithNewline = lines.lastOrNull() == ""
        if (endsWithNewline) lines.removeAt(lines.lastIndex)
        val from = edit.startLine!!
        val to = edit.endLine ?: from
        if (from < 1 || to < from || to > lines.size) {
            throw edit(index, "edit_bad_lines", "Edit $index: lines $from..$to are not in $path, which has ${lines.size} lines")
        }
        val current = lines.subList(from - 1, to).joinToString("\n")
        edit.oldText?.let { guard ->
            if (guard.replace("\r\n", "\n").trimEnd('\n') != current) {
                throw edit(index, "edit_no_match", "Edit $index: lines $from..$to of $path do not hold oldText; read them again")
            }
        }
        val replacement = edit.newText.replace("\r\n", "\n").removeSuffix("\n")
        val replaced = if (replacement.isEmpty()) emptyList() else replacement.split("\n")
        repeat(to - from + 1) { lines.removeAt(from - 1) }
        lines.addAll(from - 1, replaced)
        return lines.joinToString(eol) + if (endsWithNewline) eol else ""
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
