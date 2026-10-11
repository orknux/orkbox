package io.mszymanski.orknux.orkbox.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.Orkbox
import io.mszymanski.orknux.orkbox.model.*
import io.mszymanski.orknux.orkbox.session.Event
import io.mszymanski.orknux.orkbox.session.Execution
import io.mszymanski.orknux.orkbox.session.FileService
import io.mszymanski.orknux.orkbox.session.Session
import io.mszymanski.orknux.orkbox.session.SessionManager
import io.mszymanski.orknux.orkbox.runtime.Signal
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private const val DEFAULT_WAIT_SECONDS = 300L
private const val MAX_WAIT_SECONDS = 3600L
private const val DEFAULT_MAX_OUTPUT = 65536
private const val HEARTBEAT_MS = 15_000L

fun Application.routes(orkbox: Orkbox, token: String?, version: String) {
    val manager = orkbox.sessions
    val files = orkbox.files
    val config = manager.config
    routing {
        get("/healthz") { call.respondText("ok") }
        get("/readyz") {
            val reasons = manager.runtimes.values.mapNotNull { r -> r.unavailableReason()?.let { "${r.name}: $it" } }
            if (reasons.isEmpty()) call.respondText("ok")
            else call.respondText(reasons.joinToString("\n"), status = HttpStatusCode.ServiceUnavailable)
        }

        val openApi = OpenApi.document(version)
        get("/openapi.yaml") { call.respondText(openApi, ContentType("application", "yaml")) }
        get("/docs") { call.respondText(OpenApi.SWAGGER_UI, ContentType.Text.Html) }
        val uiPage: ByteArray = orkbox.javaClass.classLoader.getResourceAsStream("ui.html")
            ?.use { it.readBytes() }
            ?: throw IllegalStateException("ui.html is missing from the classpath")
        get("/ui") { call.respondBytes(uiPage, ContentType.Text.Html) }
        get("/") { call.respondBytes(uiPage, ContentType.Text.Html) }

        route("/v1") {
            install(BearerAuth) { this.token = token }

            get("/info") {
                call.respondJson(Info(
                    name = "orkbox",
                    version = version,
                    defaultRuntime = config.defaultRuntime,
                    runtimes = manager.runtimes.values.map { it.info(it.unavailableReason()) },
                    defaultTtlSeconds = config.defaultTtlSeconds,
                    maxTtlSeconds = config.maxTtlSeconds,
                    defaultIdleTimeoutSeconds = config.defaultIdleTimeoutSeconds,
                    maxSessions = config.maxSessions,
                ))
            }

            get("/system") { call.respondJson(systemInfo(orkbox, config, version)) }

            route("/sessions") {
                post {
                    val session = manager.create(call.receiveJson<CreateSessionRequest>())
                    call.response.header(HttpHeaders.Location, "/v1/sessions/${session.id}")
                    call.respondJson(session.toDto(), HttpStatusCode.Created)
                }
                get {
                    val q = call.request.queryParameters
                    val labels = q.getAll("label").orEmpty().associate {
                        if (':' !in it) throw ApiException.badRequest("invalid_label", "label filters are key:value")
                        it.substringBefore(':') to it.substringAfter(':')
                    }
                    val all = manager.list(q["status"], q["runtime"], labels)
                    call.respondJson(page(all, q["cursor"], q.int("limit") ?: 50, { it.id }) { it.toDto() })
                }
                route("/{id}") {
                    get {
                        val session = call.session(manager)
                        val usage = session.sandbox?.takeIf { session.status == "ready" }?.let { runCatching { it.usage() }.getOrNull() }
                        call.respondJson(session.toDto(usage))
                    }
                    patch {
                        val session = call.session(manager)
                        call.respondJson(manager.update(session, call.receiveJson<UpdateSessionRequest>()).toDto())
                    }
                    delete {
                        val session = call.session(manager)
                        manager.stop(session, "deleted")
                        call.respondJson(session.toDto())
                    }
                    get("/logs") { call.logs(call.session(manager)) }
                    executions(manager)
                    files(manager, files)
                }
            }
        }
    }
}

private fun Route.executions(manager: SessionManager) = route("/executions") {
    post {
        val session = call.session(manager)
        val request = call.receiveJson<ExecuteRequest>()
        val execution = manager.execute(session, request)
        val max = request.maxOutputBytes ?: DEFAULT_MAX_OUTPUT
        if (request.wait) manager.await(session, execution, (request.waitSeconds ?: DEFAULT_WAIT_SECONDS).coerceIn(0, MAX_WAIT_SECONDS))
        call.respondJson(execution.toDto(max), if (execution.isRunning) HttpStatusCode.Accepted else HttpStatusCode.OK)
    }
    get {
        val session = call.session(manager)
        val q = call.request.queryParameters
        val all = session.executions().reversed().filter { q["status"] == null || it.status == q["status"] }
        call.respondJson(page(all, q["cursor"], q.int("limit") ?: 50, { it.id }) { it.toDto() })
    }
    route("/{executionId}") {
        get {
            val session = call.session(manager)
            val execution = call.execution(session)
            val q = call.request.queryParameters
            if (q["wait"] == "true") manager.await(session, execution, (q.long("waitSeconds") ?: DEFAULT_WAIT_SECONDS).coerceIn(0, MAX_WAIT_SECONDS))
            else session.touch()
            call.respondJson(execution.toDto(q.int("maxOutputBytes") ?: DEFAULT_MAX_OUTPUT))
        }
        get("/output") {
            val session = call.session(manager)
            session.touch()
            call.output(call.execution(session))
        }
        post("/stdin") {
            val session = call.session(manager)
            val execution = call.execution(session)
            manager.writeStdin(session, execution, call.receiveBytes(), call.request.queryParameters["close"] == "true")
            call.respond(HttpStatusCode.NoContent)
        }
        post("/signal") {
            val session = call.session(manager)
            val execution = call.execution(session)
            val name = call.receiveJson<SignalRequest>().signal.uppercase().removePrefix("SIG")
            val signal = Signal.entries.firstOrNull { it.name == name }
                ?: throw ApiException.badRequest("invalid_signal", "signal must be one of ${Signal.entries}")
            manager.signal(session, execution, signal)
            call.respondJson(execution.toDto())
        }
    }
}

private fun Route.files(manager: SessionManager, files: FileService) {
    route("/files") {
        get {
            val q = call.request.queryParameters
            call.respondJson(files.list(
                session = call.session(manager),
                path = q["path"],
                depth = q.int("depth"),
                glob = q["glob"],
                includeHidden = q["includeHidden"] == "true",
                ignoreGitignore = q["ignoreGitignore"] == "true",
                limit = (q.int("limit") ?: 1000).coerceIn(1, 100_000),
            ))
        }
        delete {
            val q = call.request.queryParameters
            files.delete(call.session(manager), q.required("path"), q["recursive"] == "true")
            call.respond(HttpStatusCode.NoContent)
        }
        get("/content") {
            val q = call.request.queryParameters
            val content = files.read(call.session(manager), q.required("path"), q.int("startLine"), q.int("endLine"))
            call.response.header(HttpHeaders.ETag, content.etag)
            call.response.header("X-File-Size", content.size.toString())
            content.lineCount?.let { call.response.header("X-Line-Count", it.toString()) }
            val type = if (content.text) ContentType.Text.Plain.withParameter("charset", "utf-8") else ContentType.Application.OctetStream
            call.respondBytes(content.bytes, type)
        }
        put("/content") {
            val q = call.request.queryParameters
            val session = call.session(manager)
            val result = files.write(session, q.required("path"), call.receiveBytes(), q["mode"], call.request.headers[HttpHeaders.IfMatch])
            call.response.header(HttpHeaders.ETag, result.etag)
            call.respondJson(result)
        }
        post("/edit") {
            val result = files.edit(call.session(manager), call.receiveJson<EditRequest>())
            call.response.header(HttpHeaders.ETag, result.etag)
            call.respondJson(result)
        }
        get("/stat") { call.respondJson(files.stat(call.session(manager), call.request.queryParameters.required("path"))) }
        post("/move") {
            files.move(call.session(manager), call.receiveJson<MoveRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/search") { call.respondJson(files.search(call.session(manager), call.receiveJson<SearchRequest>())) }
    }
    route("/archive") {
        get {
            val session = call.session(manager)
            val path = call.request.queryParameters["path"]
            files.stat(session, path ?: "")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${session.id}.tar.gz\"")
            call.respondOutputStream(ContentType("application", "gzip")) { files.exportArchive(session, path, this) }
        }
        put {
            val session = call.session(manager)
            files.importArchive(session, call.request.queryParameters["path"], call.receiveChannel().toInputStream())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// ---- streaming ------------------------------------------------------------------------------

private suspend fun ApplicationCall.logs(session: Session) {
    val q = request.queryParameters
    val types = q["types"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val include: (String) -> Boolean = { t -> if (types != null) t in types else t != "execution.output" }
    var since = q.long("since") ?: 0
    val limit = (q.int("limit") ?: 1000).coerceIn(1, 10_000)
    if (q["follow"] != "true") {
        val items = session.events.after(since, limit, include)
        respondJson(buildJsonObject {
            put("items", ApiJson.encodeToJsonElement(ListSerializer(Event.serializer()), items))
            put("nextSeq", items.lastOrNull()?.seq ?: since)
        })
        return
    }
    respondSse { send ->
        while (true) {
            val batch = session.events.after(since, 500, include)
            for (event in batch) send(event.type, event.seq.toString(), ApiJson.encodeToString(Event.serializer(), event))
            // Skipped (filtered) events still move the cursor.
            since = maxOf(since, batch.lastOrNull()?.seq ?: since)
            if (batch.size == 500) continue
            if (!session.isActive && session.status != "stopping") {
                send("end", null, "{}")
                return@respondSse
            }
            val seen = session.events.latest.value
            val woke = withTimeoutOrNull(HEARTBEAT_MS) { session.events.latest.first { it > seen } }
            if (woke == null) send(null, null, "")
        }
    }
}

private suspend fun ApplicationCall.output(execution: Execution) {
    val q = request.queryParameters
    val buffer = when (q["stream"] ?: "combined") {
        "stdout" -> execution.stdout
        "stderr" -> execution.stderr
        "combined" -> execution.combined
        else -> throw ApiException.badRequest("invalid_stream", "stream must be stdout, stderr or combined")
    }
    var offset = q.long("offset") ?: 0
    if (q["follow"] != "true") {
        val chunk = buffer.read(offset, (q.int("limit") ?: Int.MAX_VALUE).coerceAtLeast(0))
        response.header("X-Next-Offset", chunk.next.toString())
        response.header("X-Execution-Status", execution.status)
        respondBytes(chunk.bytes, ContentType.Text.Plain.withParameter("charset", "utf-8"))
        return
    }
    respondSse { send ->
        while (true) {
            val chunk = buffer.read(offset, 64 * 1024)
            if (chunk.bytes.isNotEmpty()) {
                send("output", chunk.next.toString(), ApiJson.encodeToString(OutputChunk.serializer(),
                    OutputChunk(chunk.offset, String(chunk.bytes, Charsets.UTF_8))))
                offset = chunk.next
                continue
            }
            if (!execution.isRunning) {
                send("end", null, ApiJson.encodeToString(ExecutionDto.serializer(), execution.toDto()))
                return@respondSse
            }
            val seen = buffer.total.value
            if (seen > offset) continue
            val woke = withTimeoutOrNull(HEARTBEAT_MS) {
                merge(
                    buffer.total.filter { it > seen }.map { },
                    flow { execution.done.await(); emit(Unit) },
                ).first()
            }
            if (woke == null) send(null, null, "")
        }
    }
}

@Serializable
internal data class OutputChunk(val offset: Long, val text: String)

// ---- system overview -----------------------------------------------------------------------

private suspend fun systemInfo(orkbox: Orkbox, config: Config, version: String): SystemInfo {
    val all = orkbox.sessions.list(null, null, emptyMap())
    val active = all.filter { it.isActive }
    return SystemInfo(
        info = Info(
            name = "orkbox",
            version = version,
            defaultRuntime = config.defaultRuntime,
            runtimes = orkbox.sessions.runtimes.values.map { it.info(it.unavailableReason()) },
            defaultTtlSeconds = config.defaultTtlSeconds,
            maxTtlSeconds = config.maxTtlSeconds,
            defaultIdleTimeoutSeconds = config.defaultIdleTimeoutSeconds,
            maxSessions = config.maxSessions,
        ),
        startedAt = orkbox.startedAt.toString(),
        uptimeSeconds = Duration.between(orkbox.startedAt, Instant.now()).toSeconds(),
        host = hostInfo(config),
        sessions = SystemSessions(
            active = active.size,
            maxSessions = config.maxSessions,
            total = all.size,
            byStatus = all.groupingBy { it.status }.eachCount(),
            cpus = active.sumOf { it.resources?.cpus?.toDouble() ?: 0.0 },
            memoryMb = active.sumOf { it.resources?.memoryMb?.toLong() ?: 0L },
        ),
    )
}

private fun hostInfo(config: Config): HostInfo {
    val MB = 1_048_576L
    val jvm = Runtime.getRuntime()
    val memory = ManagementFactory.getMemoryMXBean()
    val heap = memory.heapMemoryUsage
    val os = ManagementFactory.getOperatingSystemMXBean()
    val load = os.systemLoadAverage.takeIf { it >= 0 }
    // The com.sun.management extension carries physical and swap sizes on a standard JDK;
    // where it is missing (e.g. a minimal JVM) the JVM's own memory is shown instead.
    val ext = os as? OperatingSystemMXBean
    val physical = ext?.let { it.totalPhysicalMemorySize to (it.totalPhysicalMemorySize - it.freePhysicalMemorySize) }?.takeIf { it.first >= 0 }
    val swap = ext?.let { it.totalSwapSpaceSize to (it.totalSwapSpaceSize - it.freeSwapSpaceSize) }?.takeIf { it.first >= 0 }
    val diskRoot = runCatching {
        val root = Path.of(config.local.root.toString()).toAbsolutePath()
        root.parent ?: root
    }.getOrDefault(Path.of(System.getProperty("user.dir")))
    val disk = runCatching {
        val store = Files.getFileStore(diskRoot)
        store.totalSpace to (store.totalSpace - store.usableSpace)
    }.getOrNull()
    return HostInfo(
        os = System.getProperty("os.name"),
        architecture = System.getProperty("os.arch"),
        java = System.getProperty("java.version"),
        cpuCores = jvm.availableProcessors(),
        loadAverage = load,
        memoryTotalMb = (physical?.first ?: heap.committed) / MB,
        memoryUsedMb = (physical?.second ?: heap.used) / MB,
        swapTotalMb = swap?.first?.div(MB),
        swapUsedMb = swap?.second?.div(MB),
        diskTotalMb = disk?.let { it.first / MB },
        diskUsedMb = disk?.let { it.second / MB },
        jvmUsedMb = heap.used / MB,
        jvmMaxMb = (heap.max / MB).takeIf { it > 0 },
    )
}

// ---- helpers --------------------------------------------------------------------------------

private fun ApplicationCall.session(manager: SessionManager): Session = manager.get(parameters["id"]!!)

private fun ApplicationCall.execution(session: Session): Execution = session.execution(parameters["executionId"]!!)

private fun Parameters.int(name: String) = this[name]?.toInt()
private fun Parameters.long(name: String) = this[name]?.toLong()
private fun Parameters.required(name: String) =
    this[name] ?: throw ApiException.badRequest("missing_parameter", "Query parameter '$name' is required")

private fun <T, R> page(all: List<T>, cursor: String?, limit: Int, id: (T) -> String, map: (T) -> R): Page<R> {
    val start = cursor?.let { c -> all.indexOfFirst { id(it) == c }.takeIf { it >= 0 }?.plus(1) ?: 0 } ?: 0
    val size = limit.coerceIn(1, 1000)
    val items = all.drop(start).take(size)
    val next = if (start + size < all.size) items.lastOrNull()?.let(id) else null
    return Page(items.map(map), next)
}
