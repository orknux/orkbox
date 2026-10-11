package io.mszymanski.orknux.orkbox.runtime

import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.model.Resources
import io.mszymanski.orknux.orkbox.model.RuntimeInfo
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.Usage
import java.io.InputStream
import java.io.OutputStream

/** A way of running sessions: ephemeral containers, the local system, ... */
interface Runtime {
    val name: String

    /** Provisions the sandbox for a new session. */
    suspend fun create(spec: SandboxSpec): Sandbox

    /** Null when the runtime can create sessions, otherwise why it cannot. */
    suspend fun unavailableReason(): String?

    /** Removes sandboxes left behind by a previous run of the service. */
    suspend fun cleanupOrphans() {}

    /** What `/v1/info` reports about this runtime; [reason] is from [unavailableReason]. */
    fun info(reason: String?): RuntimeInfo
}

data class SandboxSpec(
    val sessionId: String,
    val image: String?,
    val env: Map<String, String>,
    val resources: Resources?,
    val networkEnabled: Boolean,
    val caches: List<String>,
    /** Ports to publish; already checked for range and count. */
    val ports: List<Int> = emptyList(),
)

enum class Signal { INT, TERM, KILL, HUP }

/**
 * One session's isolated workspace. File operations take paths already
 * normalised by [WorkspacePath]: relative, `/`-separated, `""` for the root.
 */
interface Sandbox {
    /** The workspace root as clients see it (`/workspace`, or a host directory). */
    val workdir: String

    /** Where the ports asked for at creation can be reached; empty for a runtime that publishes nothing. */
    val ports: List<PublishedPort> get() = emptyList()

    /** Starts a command; the caller owns the returned process. */
    suspend fun spawn(spec: ProcessSpec): SandboxProcess

    /** Runs a helper program to completion inside the sandbox, from the workspace root. */
    suspend fun run(argv: List<String>, stdin: ByteArray? = null): Captured

    suspend fun readFile(path: String): ByteArray
    suspend fun writeFile(path: String, content: ByteArray, mode: String?)

    /** Null when nothing exists at [path]. */
    suspend fun stat(path: String): FileEntry?

    /** Entries below directory [path], up to [depth] levels (null: unlimited). */
    suspend fun list(path: String, depth: Int?, includeHidden: Boolean): List<FileEntry>
    suspend fun delete(path: String, recursive: Boolean)
    suspend fun move(from: String, to: String, overwrite: Boolean)
    suspend fun search(request: SearchRequest, path: String): SearchResult

    /** Writes [path] as a gzip-compressed tar to [out]. */
    suspend fun exportArchive(path: String, out: OutputStream)
    suspend fun importArchive(path: String, input: InputStream, gzip: Boolean)

    suspend fun usage(): Usage?
    suspend fun destroy()
}

data class ProcessSpec(
    val executionId: String,
    /** Exactly one of [command] (run through a shell) and [argv] is set. */
    val command: String?,
    val argv: List<String>?,
    /** Normalised workspace-relative directory. */
    val cwd: String,
    val env: Map<String, String>,
)

/** A running command: its streams, and a way to signal it. */
class SandboxProcess(
    val process: Process,
    private val signaller: suspend (Signal) -> Unit,
) {
    suspend fun signal(signal: Signal) = signaller(signal)
}
