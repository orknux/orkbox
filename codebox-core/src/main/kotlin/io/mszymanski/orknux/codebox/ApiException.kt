package io.mszymanski.orknux.codebox

/**
 * A failure the API reports as `application/problem+json`. [code] is the
 * stable, machine-readable part; [title] and [detail] are for people.
 */
class ApiException(
    val status: Int,
    val code: String,
    val title: String,
    val detail: String? = null,
    val extra: Map<String, String> = emptyMap(),
) : RuntimeException(detail ?: title) {
    companion object {
        fun badRequest(code: String, detail: String) = ApiException(400, code, "Bad request", detail)
        fun notFound(code: String, detail: String) = ApiException(404, code, "Not found", detail)
        fun conflict(code: String, detail: String) = ApiException(409, code, "Conflict", detail)

        fun sessionNotFound(id: String) = ApiException(404, "session_not_found", "Session not found", "No session $id")
        fun executionNotFound(id: String) = ApiException(404, "execution_not_found", "Execution not found", "No execution $id")
        fun fileNotFound(path: String) = ApiException(404, "file_not_found", "File not found", "No such file or directory: $path")
        fun isDirectory(path: String) = ApiException(409, "is_directory", "Is a directory", "$path is a directory")
        fun notDirectory(path: String) = ApiException(409, "not_directory", "Not a directory", "$path is not a directory")
        fun pathOutsideWorkspace(path: String) =
            ApiException(400, "path_outside_workspace", "Path outside workspace", "$path resolves outside the session workspace")
    }
}
