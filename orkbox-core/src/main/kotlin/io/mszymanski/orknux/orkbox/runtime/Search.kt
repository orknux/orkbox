package io.mszymanski.orknux.orkbox.runtime

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.model.SearchMatch
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object Search {
    /** `rg` arguments for [request] over workspace-relative [path], run from the workspace root. */
    fun ripgrepArgs(request: SearchRequest, path: String): List<String> = buildList {
        add("rg"); add("--json")
        if (!request.regex) add("--fixed-strings")
        add(if (request.caseSensitive) "--case-sensitive" else "--ignore-case")
        if (request.contextLines > 0) { add("--context"); add(request.contextLines.toString()) }
        request.glob?.let { add("--glob"); add(it) }
        add("--"); add(request.pattern)
        // Always a path: given none, rg searches stdin whenever stdin is not a
        // terminal - which under `docker exec` and a ProcessBuilder it never is -
        // so a search from the workspace root found nothing at all.
        add(path.ifEmpty { "." })
    }

    /** Parses `rg --json` output; exit code 1 means no matches, 2 an error. */
    fun parseRipgrep(result: Captured, request: SearchRequest): SearchResult {
        if (result.exitCode == 2 && result.stdout.isEmpty()) throw ApiException.badRequest("search_failed", result.err)
        val context = request.contextLines
        val matches = ArrayList<SearchMatch>()
        val pending = ArrayDeque<Pair<Int, String>>()
        var file: String? = null
        var truncated = false
        for (line in result.out.lineSequence()) {
            if (line.isBlank()) continue
            val message = Json.parseToJsonElement(line).jsonObject
            val data = message["data"]?.jsonObject ?: continue
            when (message["type"]?.jsonPrimitive?.contentOrNull) {
                "begin" -> { file = data.text("path")?.let(::normalizeRgPath); pending.clear() }
                "context" -> {
                    val number = data["line_number"]!!.jsonPrimitive.int
                    val text = data.text("lines")?.trimEnd('\n', '\r') ?: continue
                    val last = matches.lastOrNull()
                    if (last != null && last.path == file && number > last.line && number <= last.line + context) {
                        matches[matches.lastIndex] = last.copy(after = last.after + text)
                    }
                    pending.addLast(number to text)
                    while (pending.size > context) pending.removeFirst()
                }
                "match" -> {
                    if (matches.size >= request.maxResults) { truncated = true; break }
                    val number = data["line_number"]!!.jsonPrimitive.int
                    val text = data.text("lines")?.trimEnd('\n', '\r') ?: continue
                    val start = data["submatches"]?.jsonArray?.firstOrNull()?.jsonObject?.get("start")?.jsonPrimitive?.int ?: 0
                    val bytes = text.toByteArray()
                    val column = String(bytes, 0, minOf(start, bytes.size), Charsets.UTF_8).length + 1
                    val before = pending.filter { it.first >= number - context && it.first < number }.map { it.second }
                    pending.clear()
                    pending.addLast(number to text)
                    matches += SearchMatch(file ?: normalizeRgPath(data.text("path") ?: ""), number, column, text, before)
                }
            }
        }
        return SearchResult(matches, truncated)
    }

    private fun normalizeRgPath(path: String) = path.replace('\\', '/').removePrefix("./")

    private fun JsonObject.text(key: String): String? = this[key]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

    /** A plain in-process search, for hosts without ripgrep. Skips hidden and binary files. */
    fun walk(root: Path, start: Path, request: SearchRequest): SearchResult {
        val options = if (request.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = Regex(if (request.regex) request.pattern else Regex.escape(request.pattern), options)
        val glob = request.glob?.let(::Glob)
        val matches = ArrayList<SearchMatch>()
        var truncated = false
        val files = if (start.isDirectory()) {
            Files.walk(start).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p) && root.relativize(p).none { it.name.startsWith('.') }
                }.toList()
            }
        } else listOf(start)
        for (file in files.sorted()) {
            val relative = root.relativize(file).joinToString("/")
            if (glob != null && !glob.matches(relative)) continue
            val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: continue
            if (bytes.take(8192).any { it == 0.toByte() }) continue
            val lines = String(bytes, Charsets.UTF_8).lines()
            for ((index, text) in lines.withIndex()) {
                val found = regex.find(text) ?: continue
                if (matches.size >= request.maxResults) { truncated = true; break }
                val c = request.contextLines
                matches += SearchMatch(
                    path = relative,
                    line = index + 1,
                    column = found.range.first + 1,
                    text = text,
                    before = lines.subList(maxOf(0, index - c), index),
                    after = lines.subList(index + 1, minOf(lines.size, index + 1 + c)),
                )
            }
            if (truncated) break
        }
        return SearchResult(matches, truncated)
    }
}
