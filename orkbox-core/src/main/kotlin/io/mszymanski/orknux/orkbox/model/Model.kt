package io.mszymanski.orknux.orkbox.model

import kotlinx.serialization.Serializable

// Requests and responses of the Session API, see docs/session-api.md.

@Serializable
data class Resources(
    val cpus: Double? = null,
    val memoryMb: Long? = null,
    val diskMb: Long? = null,
    val pids: Long? = null,
)

@Serializable
data class GitSource(
    val url: String,
    val ref: String? = null,
    val depth: Int? = null,
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class Source(val git: GitSource? = null)

@Serializable
data class CreateSessionRequest(
    val runtime: String? = null,
    val image: String? = null,
    val env: Map<String, String> = emptyMap(),
    val resources: Resources? = null,
    val network: String? = null,
    val caches: List<String> = emptyList(),
    val ttlSeconds: Long? = null,
    val idleTimeoutSeconds: Long? = null,
    val labels: Map<String, String> = emptyMap(),
    val source: Source? = null,
)

@Serializable
data class UpdateSessionRequest(
    val ttlSeconds: Long? = null,
    val idleTimeoutSeconds: Long? = null,
    val labels: Map<String, String?>? = null,
)

@Serializable
data class ExecutionCounts(val total: Int, val running: Int)

@Serializable
data class Usage(
    val cpuSeconds: Double? = null,
    val memoryPeakMb: Long? = null,
    val memoryCurrentMb: Long? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val status: String,
    val runtime: String,
    val image: String?,
    val workdir: String?,
    val env: Map<String, String>,
    val resources: Resources?,
    val network: String,
    val caches: List<String>,
    val labels: Map<String, String>,
    val createdAt: String,
    val lastActivityAt: String,
    val expiresAt: String,
    val idleTimeoutSeconds: Long,
    val stoppedAt: String?,
    val stopReason: String?,
    val failure: String?,
    val executions: ExecutionCounts,
    val usage: Usage?,
)

@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

@Serializable
data class ExecuteRequest(
    val command: String? = null,
    val argv: List<String>? = null,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    /** Keep stdin open for `/stdin`; otherwise it is closed right away. */
    val interactive: Boolean = false,
    val timeoutSeconds: Long? = null,
    val wait: Boolean = true,
    val waitSeconds: Long? = null,
    val maxOutputBytes: Int? = null,
)

@Serializable
data class ExecutionDto(
    val id: String,
    val sessionId: String,
    val status: String,
    val command: String?,
    val argv: List<String>?,
    val cwd: String,
    val exitCode: Int?,
    val signal: String?,
    val startedAt: String,
    val finishedAt: String?,
    val durationMs: Long?,
    val stdout: String? = null,
    val stderr: String? = null,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val truncated: Boolean? = null,
)

@Serializable
data class SignalRequest(val signal: String)

@Serializable
data class FileEntry(
    val path: String,
    val type: String,
    val size: Long? = null,
    val mode: String? = null,
    val modifiedAt: String? = null,
    val target: String? = null,
)

@Serializable
data class DirectoryListing(val path: String, val entries: List<FileEntry>, val truncated: Boolean)

@Serializable
data class WriteResult(val path: String, val size: Long, val etag: String)

@Serializable
data class Edit(val oldText: String, val newText: String, val replaceAll: Boolean = false)

@Serializable
data class EditRequest(val path: String, val edits: List<Edit>)

@Serializable
data class EditResult(val path: String, val size: Long, val etag: String, val replacements: List<Int>)

@Serializable
data class MoveRequest(val from: String, val to: String, val overwrite: Boolean = false)

@Serializable
data class SearchRequest(
    val pattern: String,
    val regex: Boolean = false,
    val path: String? = null,
    val glob: String? = null,
    val caseSensitive: Boolean = true,
    val contextLines: Int = 0,
    val maxResults: Int = 200,
)

@Serializable
data class SearchMatch(
    val path: String,
    val line: Int,
    val column: Int,
    val text: String,
    val before: List<String> = emptyList(),
    val after: List<String> = emptyList(),
)

@Serializable
data class SearchResult(val matches: List<SearchMatch>, val truncated: Boolean)

@Serializable
data class RuntimeInfo(
    val name: String,
    val available: Boolean,
    val unavailableReason: String? = null,
    val workdir: String? = null,
    /** How a `command` string is run. */
    val shell: List<String>,
    val os: String,
    val images: List<String>? = null,
    val defaultImage: String? = null,
    val defaultResources: Resources? = null,
    val maxResources: Resources? = null,
    val diskLimitEnforced: Boolean? = null,
    val networkModes: List<String>,
    val caches: List<String>,
)

@Serializable
data class Info(
    val name: String,
    val version: String,
    val defaultRuntime: String,
    val runtimes: List<RuntimeInfo>,
    val defaultTtlSeconds: Long,
    val maxTtlSeconds: Long,
    val defaultIdleTimeoutSeconds: Long,
    val maxSessions: Int,
)

@Serializable
data class HostInfo(
    val os: String,
    val architecture: String,
    val java: String,
    val cpuCores: Int,
    val loadAverage: Double? = null,
    val memoryTotalMb: Long? = null,
    val memoryUsedMb: Long? = null,
    val swapTotalMb: Long? = null,
    val swapUsedMb: Long? = null,
    val diskTotalMb: Long? = null,
    val diskUsedMb: Long? = null,
    val jvmUsedMb: Long? = null,
    val jvmMaxMb: Long? = null,
)

@Serializable
data class SystemSessions(
    val active: Int,
    val maxSessions: Int,
    val total: Int,
    val byStatus: Map<String, Int>,
    val cpus: Double,
    val memoryMb: Long,
)

@Serializable
data class SystemInfo(
    val info: Info,
    val startedAt: String,
    val uptimeSeconds: Long,
    val host: HostInfo,
    val sessions: SystemSessions,
)
