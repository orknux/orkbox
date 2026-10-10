package io.mszymanski.orknux.codebox.session

import io.mszymanski.orknux.codebox.ApiException
import io.mszymanski.orknux.codebox.model.ExecutionCounts
import io.mszymanski.orknux.codebox.model.ExecutionDto
import io.mszymanski.orknux.codebox.model.Resources
import io.mszymanski.orknux.codebox.model.SessionDto
import io.mszymanski.orknux.codebox.model.Usage
import io.mszymanski.orknux.codebox.runtime.Sandbox
import io.mszymanski.orknux.codebox.runtime.SandboxProcess
import io.mszymanski.orknux.codebox.runtime.Signal
import kotlinx.coroutines.CompletableDeferred
import java.io.OutputStream
import java.time.Duration
import java.time.Instant

object SessionStatus {
    const val CREATING = "creating"
    const val READY = "ready"
    const val STOPPING = "stopping"
    const val STOPPED = "stopped"
    const val FAILED = "failed"
}

object ExecutionStatus {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val TIMED_OUT = "timed_out"
    const val KILLED = "killed"
}

class Session(
    val id: String,
    val runtime: String,
    val image: String?,
    val env: Map<String, String>,
    val resources: Resources?,
    val network: String,
    val caches: List<String>,
    labels: Map<String, String>,
    ttlSeconds: Long,
    @Volatile var idleTimeoutSeconds: Long,
    val events: EventLog,
) {
    val createdAt: Instant = Instant.now()

    @Volatile var status: String = SessionStatus.CREATING
    @Volatile var sandbox: Sandbox? = null
    @Volatile var lastActivityAt: Instant = createdAt
    @Volatile var expiresAt: Instant = createdAt.plusSeconds(ttlSeconds)
    @Volatile var stoppedAt: Instant? = null
    @Volatile var stopReason: String? = null
    @Volatile var failure: String? = null

    private val _labels = LinkedHashMap(labels)
    val labels: Map<String, String> @Synchronized get() = LinkedHashMap(_labels)

    @Synchronized
    fun updateLabels(changes: Map<String, String?>) {
        for ((key, value) in changes) if (value == null) _labels.remove(key) else _labels[key] = value
    }

    private val executions = LinkedHashMap<String, Execution>()

    @Synchronized
    fun add(execution: Execution) { executions[execution.id] = execution }

    @Synchronized
    fun executions(): List<Execution> = executions.values.toList()

    fun execution(id: String): Execution = synchronized(this) { executions[id] } ?: throw ApiException.executionNotFound(id)

    fun touch() { lastActivityAt = Instant.now() }

    val isActive: Boolean get() = status == SessionStatus.CREATING || status == SessionStatus.READY

    fun requireReady(): Sandbox {
        val sandbox = sandbox
        if (status != SessionStatus.READY || sandbox == null) {
            throw ApiException(409, "session_not_ready", "Session not ready", "Session $id is $status")
        }
        touch()
        return sandbox
    }

    fun toDto(usage: Usage? = null): SessionDto {
        val all = executions()
        return SessionDto(
            id = id,
            status = status,
            runtime = runtime,
            image = image,
            workdir = sandbox?.workdir,
            env = env,
            resources = resources,
            network = network,
            caches = caches,
            labels = labels,
            createdAt = createdAt.toString(),
            lastActivityAt = lastActivityAt.toString(),
            expiresAt = expiresAt.toString(),
            idleTimeoutSeconds = idleTimeoutSeconds,
            stoppedAt = stoppedAt?.toString(),
            stopReason = stopReason,
            failure = failure,
            executions = ExecutionCounts(all.size, all.count { it.status == ExecutionStatus.RUNNING }),
            usage = usage,
        )
    }
}

class Execution(
    val id: String,
    val sessionId: String,
    val command: String?,
    val argv: List<String>?,
    val cwd: String,
    outputCapacity: Int,
) {
    val startedAt: Instant = Instant.now()
    val stdout = OutputBuffer(outputCapacity)
    val stderr = OutputBuffer(outputCapacity)
    val combined = OutputBuffer(outputCapacity)
    val done = CompletableDeferred<Unit>()

    @Volatile var status: String = ExecutionStatus.RUNNING
    @Volatile var exitCode: Int? = null
    @Volatile var finishedAt: Instant? = null
    @Volatile var process: SandboxProcess? = null
    @Volatile var stdin: OutputStream? = null

    /** The last signal sent through the API (or by the service on stop/timeout). */
    @Volatile var signal: Signal? = null
    @Volatile var timedOut = false

    val isRunning get() = status == ExecutionStatus.RUNNING

    fun toDto(maxOutputBytes: Int? = null): ExecutionDto {
        val out = maxOutputBytes?.let { stdout.excerpt(it) }
        val err = maxOutputBytes?.let { stderr.excerpt(it) }
        return ExecutionDto(
            id = id,
            sessionId = sessionId,
            status = status,
            command = command,
            argv = argv,
            cwd = cwd,
            exitCode = exitCode,
            signal = signal?.name,
            startedAt = startedAt.toString(),
            finishedAt = finishedAt?.toString(),
            durationMs = finishedAt?.let { Duration.between(startedAt, it).toMillis() },
            stdout = out?.text,
            stderr = err?.text,
            stdoutBytes = stdout.total.value,
            stderrBytes = stderr.total.value,
            truncated = if (out != null && err != null) out.truncated || err.truncated else null,
        )
    }
}
