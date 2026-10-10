package io.mszymanski.orknux.orkbox.runtime

/**
 * A gitignore-flavoured glob over `/`-separated relative paths: `*` and `?`
 * stay within one segment, `**` crosses segments, `{a,b}` is alternation and
 * `[...]` a character class. A glob without `/` matches the file name at any
 * depth, as in ripgrep and `.gitignore`.
 */
class Glob(pattern: String) {
    private val nameOnly = '/' !in pattern
    private val regex = Regex(translate(pattern.trimStart('/')))

    fun matches(path: String): Boolean =
        regex.matches(if (nameOnly) path.substringAfterLast('/') else path)

    private fun translate(glob: String): String {
        val out = StringBuilder()
        var i = 0
        var braces = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '[' -> {
                    val end = glob.indexOf(']', i + 2)
                    if (end < 0) out.append("\\[")
                    else {
                        val body = glob.substring(i + 1, end).let { if (it.startsWith('!')) "^" + it.substring(1) else it }
                        out.append('[').append(body.replace("\\", "\\\\")).append(']')
                        i = end
                    }
                }
                c == '{' -> { braces++; out.append("(?:") }
                c == '}' && braces > 0 -> { braces--; out.append(')') }
                c == ',' && braces > 0 -> out.append('|')
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return out.toString()
    }
}
