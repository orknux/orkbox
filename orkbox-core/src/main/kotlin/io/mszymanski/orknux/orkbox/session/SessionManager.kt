package io.mszymanski.orknux.orkbox.session

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.GitSource
import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.model.Resources
import io.mszymanski.orknux.orkbox.model.UpdateSessionRequest
import io.mszymanski.orknux.orkbox.runtime.DockerRuntime
import io.mszymanski.orknux.orkbox.runtime.ProcessSpec
import io.mszymanski.orknux.orkbox.runtime.ProgramNotFoundException
import io.mszymanski.orknux.orkbox.runtime.Runtime
import io.mszymanski.orknux.orkbox.runtime.SandboxSpec
import io.mszymanski.orknux.orkbox.runtime.Signal
import io.mszymanski.orknux.orkbox.runtime.WorkspacePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Owns every session: their lifecycle, executions and the reaper. */
class SessionManager(
    val config: Config,
    val runtimes: Map<String, Runtime>,
) {
    private val log = LoggerFactory.getLogger(SessionManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()

    fun start() {
        scope.launch {
            runtimes.values.forEach { runCatching { it.cleanupOrphans() }.onFailure { e -> log.warn("Cleanup failed", e) } }
            while (isActive) {
                delay(5_000)
                runCatching { reap() }.onFailure { log.warn("Reaper failed", it) }
            }
        }
    }

    suspend fun shutdown() {
        sessions.values.filter { it.isActive }.map { scope.launch { stop(it, "server_shutdown") } }.joinAll()
        scope.cancel()
    }

    fun get(id: String): Session = sessions[id] ?: throw ApiException.sessionNotFound(id)

    fun list(status: String?, runtime: String?, labels: Map<String, String>): List<Session> =
        sessions.values
            .filter { status == null || it.status == status }
            .filter { runtime == null || it.runtime == runtime }
            .filter { s -> labels.all { (k, v) -> s.labels[k] == v } }
            .sortedWith(compareByDescending<Session> { it.createdAt }.thenBy { it.id })

    // ---- lifecycle -------------------------------------------------------------------------

    suspend fun create(request: CreateSessionRequest): Session {
        val runtimeName = request.runtime ?: config.defaultRuntime
        val runtime = runtimes[runtimeName]
            ?: throw ApiException.badRequest("runtime_not_enabled", "Runtime '$runtimeName' is not enabled; enabled: ${runtimes.keys}")
        val docker = runtimeName == Config.RUNTIME_DOCKER

        val network = request.network ?: "full"
        if (network != "full" && network != "none") throw ApiException.badRequest("invalid_network", "network must be 'full' or 'none'")
        if (!docker && network == "none") throw ApiException.badRequest("unsupported_for_runtime", "The local runtime cannot disable networking")

        val image = if (docker) request.image ?: config.docker.defaultImage else null
        if (docker && !config.docker.allows(image!!)) throw ApiException.badRequest("image_not_allowed", "Image '$image' is not on the allowlist")
        if (!docker && request.image != null) throw ApiException.badRequest("unsupported_for_runtime", "The local runtime does not use images")

        val caches = if (docker) request.caches.distinct() else emptyList()
        caches.firstOrNull { it !in DockerRuntime.CACHES }?.let {
            throw ApiException.badRequest("unknown_cache", "Unknown cache '$it'; available: ${DockerRuntime.CACHES.keys}")
        }
        val resources = if (docker) resources(request.resources) else null

        val ttl = request.ttlSeconds ?: config.defaultTtlSeconds
        checkTtl(ttl)
        val idle = request.idleTimeoutSeconds ?: config.defaultIdleTimeoutSeconds
        if (idle <= 0) throw ApiException.badRequest("invalid_idle_timeout", "idleTimeoutSeconds must be positive")
        request.source?.git?.let { if (it.url.isBlank()) throw ApiException.badRequest("invalid_source", "source.git.url is required") }

        val session = Session(
            id = Ids.session(),
            runtime = runtimeName,
            image = image,
            env = config.sessionEnv + request.env,
            resources = resources,
            network = network,
            caches = caches,
            labels = request.labels,
            ttlSeconds = ttl,
            idleTimeoutSeconds = idle,
            events = EventLog(config.maxEvents),
        )
        session.forwards = PortForwarder(config.publishHost)
        synchronized(sessions) {
            requireRoom()
            sessions[session.id] = session
        }
        session.events.append("session.created", buildJsonObject {
            put("runtime", runtimeName)
            image?.let { put("image", it) }
        })

        val started = Instant.now()
        try {
            val sandbox = runtime.create(
                SandboxSpec(session.id, image, session.env, resources, network == "full", caches),
            )
            session.sandbox = sandbox
            if (session.status != SessionStatus.CREATING) {
                // Deleted while it was being created.
                sandbox.destroy()
                return session
            }
            session.status = SessionStatus.READY
            request.source?.git?.let { clone(session, it) }
            session.events.append("session.ready", buildJsonObject {
                put("startupMs", Duration.between(started, Instant.now()).toMillis())
            })
        } catch (e: Exception) {
            log.warn("Session {} failed to start: {}", session.id, e.message)
            fail(session, e.message ?: e.toString())
        }
        session.touch()
        return session
    }

    /** Refuses when [Config.maxSessions] sessions are working; idle ones are not counted. Called holding `sessions`. */
    private fun requireRoom() {
        if (sessions.values.count { it.isBusy } >= config.maxSessions) {
            throw ApiException(
                429, "too_many_sessions", "Too many sessions",
                "At most ${config.maxSessions} sessions may be working at once; try again when one finishes",
            )
        }
    }

    private fun resources(requested: Resources?): Resources {
        val d = config.docker
        fun <T : Comparable<T>> capped(name: String, value: T?, default: T, max: T): T {
            val v = value ?: default
            if (v > max) throw ApiException.badRequest("limit_exceeded", "resources.$name may be at most $max")
            return v
        }
        return Resources(
            cpus = capped("cpus", requested?.cpus, d.defaultCpus, d.maxCpus),
            memoryMb = capped("memoryMb", requested?.memoryMb, d.defaultMemoryMb, d.maxMemoryMb),
            diskMb = if (d.diskLimit) capped("diskMb", requested?.diskMb, d.defaultDiskMb, d.maxDiskMb) else null,
            pids = capped("pids", requested?.pids, d.defaultPids, d.maxPids),
        )
    }

    private fun checkTtl(ttl: Long) {
        if (ttl <= 0 || ttl > config.maxTtlSeconds) {
            throw ApiException.badRequest("invalid_ttl", "ttlSeconds must be between 1 and ${config.maxTtlSeconds}")
        }
    }

    /** Clones [source] into the workspace as ordinary, logged executions. */
    private suspend fun clone(session: Session, source: GitSource) {
        val env = source.env + ("GIT_TERMINAL_PROMPT" to "0")
        val depth = source.depth?.let { listOf("--depth", it.toString()) } ?: emptyList()
        val ref = source.ref
        val steps = if (ref != null && Regex("[0-9a-fA-F]{7,40}").matches(ref)) {
            listOf(
                listOf("git", "init", "--quiet", "."),
                listOf("git", "config", "core.autocrlf", "false"),
                listOf("git", "remote", "add", "origin", source.url),
                listOf("git", "fetch", "--quiet") + depth + listOf("origin", ref),
                listOf("git", "checkout", "--quiet", "FETCH_HEAD"),
            )
        } else {
            /*
             * The repository's own line endings, whatever the host's git is set
             * to: Git for Windows checks out CRLF by default, and a model reading
             * a file with \r in every line copies it into the text it asks to
             * replace - which then matches nothing.
             */
            listOf(
                listOf("git", "-c", "core.autocrlf=false", "clone", "--quiet") + depth +
                    (ref?.let { listOf("--branch", it) } ?: emptyList()) + listOf("--", source.url, "."),
                listOf("git", "config", "core.autocrlf", "false"),
            )
        }
        for (argv in steps) {
            val execution = execute(session, ExecuteRequest(argv = argv, env = env, timeoutSeconds = 1800, wait = false))
            execution.done.await()
            if (execution.status != ExecutionStatus.SUCCEEDED) {
                val reason = execution.stderr.excerpt(2000).text.trim().ifEmpty { "exit code ${execution.exitCode}" }
                // The subcommand, past any "-c name=value" options in front of it.
                val step = argv.drop(1).filterIndexed { i, it -> !it.startsWith("-") && (i == 0 || argv[i] != "-c") }.firstOrNull() ?: argv[1]
                throw IllegalStateException("git $step failed: $reason")
            }
        }
    }

    private suspend fun fail(session: Session, reason: String) {
        session.failure = reason
        killExecutions(session)
        session.sandbox?.let { runCatching { it.destroy() } }
        session.status = SessionStatus.FAILED
        session.stopReason = "failed"
        session.stoppedAt = Instant.now()
        session.events.append("session.failed", buildJsonObject { put("failure", reason) })
    }

    fun update(session: Session, request: UpdateSessionRequest): Session {
        if (!session.isActive) throw ApiException(409, "session_not_ready", "Session not ready", "Session ${session.id} is ${session.status}")
        val now = Instant.now()
        request.ttlSeconds?.let {
            if (it <= 0 || Duration.between(session.createdAt, now).seconds + it > config.maxTtlSeconds) {
                throw ApiException.badRequest("invalid_ttl", "A session may live at most ${config.maxTtlSeconds} seconds in total")
            }
            session.expiresAt = now.plusSeconds(it)
        }
        request.idleTimeoutSeconds?.let {
            if (it <= 0) throw ApiException.badRequest("invalid_idle_timeout", "idleTimeoutSeconds must be positive")
            session.idleTimeoutSeconds = it
        }
        request.labels?.let { session.updateLabels(it) }
        session.touch()
        session.events.append("session.updated", buildJsonObject {
            put("expiresAt", session.expiresAt.toString())
            put("idleTimeoutSeconds", session.idleTimeoutSeconds)
        })
        return session
    }

    suspend fun stop(session: Session, reason: String) {
        synchronized(session) {
            if (!session.isActive) return
            session.status = SessionStatus.STOPPING
        }
        session.events.append("session.stopping", buildJsonObject { put("reason", reason) })
        withContext(NonCancellable) {
            session.forwards.closeAll()
            killExecutions(session)
            session.sandbox?.let { sandbox ->
                runCatching { sandbox.destroy() }.onFailure { log.warn("Destroying session {} failed", session.id, it) }
            }
        }
        session.status = SessionStatus.STOPPED
        session.stopReason = reason
        session.stoppedAt = Instant.now()
        session.events.append("session.stopped", buildJsonObject { put("reason", reason) })
    }

    // ---- ports -----------------------------------------------------------------------------

    /**
     * Forwards [port] inside [session] to a random port on the configured
     * address, or answers the forward already open for it.
     */
    fun forward(session: Session, port: Int): PublishedPort {
        if (port !in 1..65535) throw ApiException.badRequest("invalid_port", "Port $port is not between 1 and 65535")
        val sandbox = session.requireReady()
        session.forwards.find(port)?.let { return it }
        if (session.forwards.list().size >= config.maxPorts) {
            throw ApiException.badRequest("too_many_ports", "At most ${config.maxPorts} ports may be forwarded per session")
        }
        val published = session.forwards.open(port) { sandbox.connect(it) }
        session.events.append("port.opened", buildJsonObject {
            put("port", port)
            put("hostPort", published.hostPort)
        })
        return published
    }

    fun unforward(session: Session, port: Int) {
        if (!session.forwards.close(port)) throw ApiException.notFound("port_not_forwarded", "Port $port is not forwarded")
        session.events.append("port.closed", buildJsonObject { put("port", port) })
    }

    private suspend fun killExecutions(session: Session) {
        for (execution in session.executions().filter { it.isRunning }) {
            execution.signal = Signal.KILL
            runCatching { execution.process?.signal(Signal.KILL) }
            execution.process?.process?.destroyForcibly()
        }
    }

    private suspend fun reap() {
        val now = Instant.now()
        for (session in sessions.values) {
            when {
                session.status == SessionStatus.READY && now.isAfter(session.expiresAt) -> stop(session, "ttl_expired")
                session.status == SessionStatus.READY &&
                    session.executions().none { it.isRunning } &&
                    now.isAfter(session.lastActivityAt.plusSeconds(session.idleTimeoutSeconds)) -> stop(session, "idle_timeout")
                !session.isActive && session.status != SessionStatus.STOPPING &&
                    session.stoppedAt?.plusSeconds(config.retentionSeconds)?.isBefore(now) == true -> sessions.remove(session.id)
            }
        }
    }

    // ---- executions ------------------------------------------------------------------------

    suspend fun execute(session: Session, request: ExecuteRequest): Execution {
        val sandbox = session.requireReady()
        if ((request.command == null) == (request.argv == null)) {
            throw ApiException.badRequest("invalid_command", "Exactly one of 'command' and 'argv' is required")
        }
        if (request.argv != null && request.argv.isEmpty()) throw ApiException.badRequest("invalid_command", "argv must not be empty")
        if (request.stdin != null && request.interactive) {
            throw ApiException.badRequest("invalid_stdin", "'stdin' and 'interactive' cannot be combined")
        }
        val timeout = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
        if (timeout <= 0) throw ApiException.badRequest("invalid_timeout", "timeoutSeconds must be positive")
        val cwd = WorkspacePath.normalize(request.cwd, sandbox.workdir, caseInsensitive = session.runtime == Config.RUNTIME_LOCAL)

        val execution = Execution(Ids.execution(), session.id, request.command, request.argv, cwd, config.maxOutputBytes)
        // An idle session starting work takes a place; one already working has it.
        synchronized(sessions) {
            if (!session.isBusy) requireRoom()
            session.add(execution)
        }
        session.events.append("execution.started", buildJsonObject {
            put("executionId", execution.id)
            request.command?.let { put("command", it) }
            request.argv?.let { argv -> putJsonArray("argv") { argv.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            put("cwd", cwd)
        })

        val process = try {
            sandbox.spawn(ProcessSpec(execution.id, request.command, request.argv, cwd, request.env))
        } catch (e: ProgramNotFoundException) {
            // Same outcome a shell gives for a missing program.
            execution.stderr.append((e.message + "\n").toByteArray())
            execution.combined.append((e.message + "\n").toByteArray())
            finish(session, execution, 127)
            return execution
        } catch (e: Exception) {
            session.events.append("execution.finished", buildJsonObject {
                put("executionId", execution.id); put("status", ExecutionStatus.FAILED); put("error", e.message)
            })
            execution.status = ExecutionStatus.FAILED
            execution.finishedAt = Instant.now()
            execution.done.complete(Unit)
            throw e
        }
        execution.process = process
        if (request.interactive) execution.stdin = process.process.outputStream

        scope.launch {
            val pumps = listOf(
                launch { pump(session, execution, process.process.inputStream, execution.stdout, "stdout") },
                launch { pump(session, execution, process.process.errorStream, execution.stderr, "stderr") },
            )
            if (!request.interactive) launch { feed(process.process.outputStream, request.stdin) }
            val watchdog = launch {
                delay(timeout * 1000)
                execution.timedOut = true
                runCatching { process.signal(Signal.TERM) }
                delay(config.killGraceSeconds * 1000)
                runCatching { process.signal(Signal.KILL) }
                process.process.destroyForcibly()
            }
            val code = runInterruptible { process.process.waitFor() }
            watchdog.cancel()
            // The streams end once every holder of the pipe is gone; don't wait on stray grandchildren forever.
            withTimeoutOrNull(5_000) { pumps.joinAll() }
            finish(session, execution, code)
        }
        return execution
    }

    /** Writes the request's stdin, if any, and closes it so the command sees end of input. */
    private suspend fun feed(stdin: java.io.OutputStream, input: String?) = withContext(Dispatchers.IO) {
        try {
            stdin.use { s -> input?.let { s.write(it.toByteArray()) } }
        } catch (_: IOException) {
            // The command exited without reading it.
        }
    }

    private suspend fun pump(session: Session, execution: Execution, input: InputStream, buffer: OutputBuffer, stream: String) {
        val chunk = ByteArray(8192)
        withContext(Dispatchers.IO) {
            input.use {
                while (true) {
                    val n = try { runInterruptible { input.read(chunk) } } catch (_: IOException) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(execution) {
                        buffer.append(chunk, 0, n)
                        execution.combined.append(chunk, 0, n)
                    }
                    session.events.append("execution.output", buildJsonObject {
                        put("executionId", execution.id)
                        put("stream", stream)
                        put("text", String(chunk, 0, n, Charsets.UTF_8))
                    })
                }
            }
        }
    }

    private fun finish(session: Session, execution: Execution, code: Int) {
        execution.stdin?.let { runCatching { it.close() } }
        execution.exitCode = code
        execution.finishedAt = Instant.now()
        execution.status = when {
            execution.timedOut -> ExecutionStatus.TIMED_OUT
            execution.signal != null -> ExecutionStatus.KILLED
            code == 0 -> ExecutionStatus.SUCCEEDED
            else -> ExecutionStatus.FAILED
        }
        session.events.append("execution.finished", buildJsonObject {
            put("executionId", execution.id)
            put("status", execution.status)
            put("exitCode", code)
            put("durationMs", Duration.between(execution.startedAt, execution.finishedAt).toMillis())
        })
        execution.done.complete(Unit)
    }

    /** Waits up to [seconds] for [execution] to finish; true if it did. */
    suspend fun await(session: Session, execution: Execution, seconds: Long): Boolean {
        val finished = withTimeoutOrNull(seconds * 1000) { execution.done.await() } != null
        session.touch()
        return finished
    }

    suspend fun signal(session: Session, execution: Execution, signal: Signal) {
        session.requireReady()
        if (!execution.isRunning) throw ApiException.conflict("execution_finished", "Execution ${execution.id} has already finished")
        execution.signal = signal
        execution.process?.signal(signal)
        if (signal == Signal.KILL) execution.process?.process?.destroyForcibly()
    }

    suspend fun writeStdin(session: Session, execution: Execution, input: ByteArray, close: Boolean) {
        session.requireReady()
        val stdin = execution.stdin
            ?: throw ApiException.conflict("stdin_closed", "Execution ${execution.id} was not started with interactive=true, or its stdin is closed")
        if (!execution.isRunning) throw ApiException.conflict("execution_finished", "Execution ${execution.id} has already finished")
        withContext(Dispatchers.IO) {
            try {
                stdin.write(input)
                stdin.flush()
                if (close) {
                    stdin.close()
                    execution.stdin = null
                }
            } catch (e: IOException) {
                execution.stdin = null
                throw ApiException.conflict("stdin_closed", "The process closed its stdin: ${e.message}")
            }
        }
    }

    fun log(session: Session, type: String, data: JsonObject) {
        session.events.append(type, data)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
    }
}
