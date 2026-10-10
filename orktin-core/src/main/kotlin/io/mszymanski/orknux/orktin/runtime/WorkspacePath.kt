package io.mszymanski.orknux.orktin.runtime

import io.mszymanski.orknux.orktin.ApiException

object WorkspacePath {
    /**
     * Turns a client path into a normalised workspace-relative one: `/`
     * separated, no `.` or `..` segments, `""` for the root.
     *
     * Relative paths are taken from the workspace root. Absolute ones are
     * accepted only when they point inside [workdir]. Anything that climbs out
     * of the workspace is rejected.
     */
    fun normalize(input: String?, workdir: String, caseInsensitive: Boolean = false): String {
        if (input.isNullOrEmpty()) return ""
        val path = input.replace('\\', '/')
        val root = workdir.replace('\\', '/').trimEnd('/')
        val relative = when {
            isAbsolute(path) -> {
                val within = if (path.length == root.length) path.equals(root, caseInsensitive)
                else path.startsWith("$root/", caseInsensitive)
                if (!within) throw ApiException.pathOutsideWorkspace(input)
                path.substring(root.length)
            }
            else -> path
        }
        val segments = ArrayList<String>()
        for (segment in relative.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isEmpty()) throw ApiException.pathOutsideWorkspace(input) else segments.removeLast()
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }

    private fun isAbsolute(path: String) =
        path.startsWith('/') || (path.length >= 2 && path[1] == ':' && path[0].isLetter())

    /** The parent of a normalised path, `""` for top-level entries. */
    fun parent(path: String) = path.substringBeforeLast('/', "")

    fun join(base: String, child: String) = if (base.isEmpty()) child else "$base/$child"
}
