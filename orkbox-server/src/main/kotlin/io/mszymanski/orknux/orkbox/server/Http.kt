package io.mszymanski.orknux.orkbox.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.utils.io.toByteArray
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import io.mszymanski.orknux.orkbox.ApiException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

val ApiJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

private val log = LoggerFactory.getLogger("io.mszymanski.orknux.orkbox.server")
private val ProblemJson = ContentType("application", "problem+json")

fun Application.installHttp() {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondProblem(e) }
        exception<BadRequestException> { call, e ->
            val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SerializationException } ?: e
            call.respondProblem(ApiException(400, "invalid_request", "Invalid request", cause.message))
        }
        exception<SerializationException> { call, e ->
            call.respondProblem(ApiException(400, "invalid_request", "Invalid request", e.message))
        }
        exception<NumberFormatException> { call, e ->
            call.respondProblem(ApiException(400, "invalid_request", "Invalid request", "Invalid number: ${e.message}"))
        }
        exception<Throwable> { call, e ->
            log.error("{} {} failed", call.request.httpMethod.value, call.request.path(), e)
            call.respondProblem(ApiException(500, "internal_error", "Internal error", e.message))
        }
    }
}

// JSON goes through serializers resolved at compile time rather than Ktor content
// negotiation, which looks them up reflectively and so fails in a native image.

suspend inline fun <reified T> ApplicationCall.receiveJson(): T {
    val text = receiveText()
    return ApiJson.decodeFromString(serializer<T>(), text.ifBlank { "{}" })
}

suspend fun ApplicationCall.receiveBytes(): ByteArray = receiveChannel().toByteArray()

suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(TextContent(ApiJson.encodeToString(serializer<T>(), value), ContentType.Application.Json, status))

suspend fun ApplicationCall.respondProblem(e: ApiException) {
    val body = buildJsonObject {
        put("type", "about:blank")
        put("title", e.title)
        put("status", e.status)
        put("code", e.code)
        e.detail?.let { put("detail", it) }
        e.extra.forEach { (k, v) -> v.toIntOrNull()?.let { n -> put(k, n) } ?: put(k, v) }
    }
    respond(TextContent(body.toString(), ProblemJson, HttpStatusCode.fromValue(e.status)))
}

/**
 * Bearer-token check for everything under the route it is installed on.
 * [check] decides whether a presented bearer token is valid; the default
 * lets everything through, so a route with no authentication simply leaves it
 * unset. [exempt] names request paths that must stay public (e.g. `/v1/auth`
 * and `/v1/login`, which exist to obtain a token).
 */
class BearerAuthConfig {
    var check: (String) -> Boolean = { true }
    var exempt: (String) -> Boolean = { false }
}

val BearerAuth = createRouteScopedPlugin("BearerAuth", ::BearerAuthConfig) {
    onCall { call ->
        if (pluginConfig.exempt(call.request.path())) return@onCall
        val header = call.request.headers[HttpHeaders.Authorization]
        val given = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (given == null || !pluginConfig.check(given)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondProblem(ApiException(401, "unauthorized", "Unauthorized", "A valid bearer token is required"))
        }
    }
}

/**
 * How the API is authenticated. [none] turns it off, [token] keeps a single
 * static bearer secret, [userPass] lets a known user exchange their password
 * for a bearer token through `POST /v1/login` (nothing static to share).
 */
sealed interface Auth {
    object None : Auth
    data class Token(val token: String) : Auth
    class UserPass(val user: String, val password: String) : Auth {
        private val tokens = ConcurrentHashMap.newKeySet<String>()
        private val random = SecureRandom()
        private val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"

        fun verify(user: String, password: String): Boolean =
            user == this.user && MessageDigest.isEqual(this.password.toByteArray(), password.toByteArray())

        fun issueToken(): String = buildString { repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) } }.also { tokens.add(it) }

        fun checkToken(token: String): Boolean = token in tokens
    }
}

/** A Server-Sent Events response; [block] gets a function that sends one event. */
suspend fun ApplicationCall.respondSse(block: suspend (send: suspend (event: String?, id: String?, data: String) -> Unit) -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("X-Accel-Buffering", "no")
    respondBytesWriter(ContentType.Text.EventStream) {
        block { event, id, data ->
            val frame = buildString {
                if (event == null && id == null && data.isEmpty()) append(": ping\n")
                event?.let { append("event: ").append(it).append('\n') }
                id?.let { append("id: ").append(it).append('\n') }
                if (data.isNotEmpty() || event != null) data.split('\n').forEach { append("data: ").append(it).append('\n') }
                append('\n')
            }
            writeStringUtf8(frame)
            flush()
        }
    }
}
