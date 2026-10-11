package io.mszymanski.orknux.orkbox.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.LocalConfig
import io.mszymanski.orknux.orkbox.Orkbox
import io.mszymanski.orknux.orkbox.model.LoginRequest
import io.mszymanski.orknux.orkbox.model.LoginResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The three authentication modes: none, a static token, and user/password. */
class AuthTest {
    private fun api(server: ServerConfig, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val orkbox = Orkbox(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("orkbox-auth-test")),
        ))
        application { orkbox(orkbox, server) }
        try {
            block(client)
        } finally {
            orkbox.close()
        }
    }

    private suspend fun HttpClient.json(path: String): Pair<HttpStatusCode, JsonObject> {
        val res = get(path)
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.login(user: String, password: String, token: String? = null): Pair<HttpStatusCode, JsonObject> {
        val res = post("/v1/login") {
            if (token != null) bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LoginRequest.serializer(), LoginRequest(user, password)))
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun `user and password exchange for a bearer token`(): Unit = api(ServerConfig(user = "alice", password = "s3cret")) { client ->
        // The mode is discoverable without any credentials.
        val (authStatus, authBody) = client.json("/v1/auth")
        assertEquals(HttpStatusCode.OK, authStatus)
        assertEquals("userpass", authBody["mode"]!!.jsonPrimitive.content)

        // Wrong credentials are rejected.
        assertEquals(HttpStatusCode.Unauthorized, client.login("alice", "wrong").first)
        assertEquals(HttpStatusCode.Unauthorized, client.login("mallory", "s3cret").first)

        // Correct credentials mint a token that works on the API.
        val (loginStatus, loginBody) = client.login("alice", "s3cret")
        assertEquals(HttpStatusCode.OK, loginStatus)
        val token = loginBody["token"]!!.jsonPrimitive.content
        assertTrue(token.isNotEmpty())

        val (infoStatus, infoBody) = client.json("/v1/info")
        assertEquals(HttpStatusCode.Unauthorized, infoStatus, "the API is still protected before login")
        val (authed, _) = client.json("/v1/info")
        assertEquals(HttpStatusCode.Unauthorized, authed)
        val ok = client.get("/v1/info") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, ok.status)

        // The minted token is not a fixed secret: a second login gives a different one.
        val second = client.login("alice", "s3cret").second["token"]!!.jsonPrimitive.content
        assertTrue(second.isNotEmpty() && second != token)
    }

    @Test
    fun `token mode keeps the static token and refuses login`(): Unit = api(ServerConfig(token = "change-me")) { client ->
        val (authStatus, authBody) = client.json("/v1/auth")
        assertEquals(HttpStatusCode.OK, authStatus)
        assertEquals("token", authBody["mode"]!!.jsonPrimitive.content)

        val (loginStatus, loginBody) = client.login("alice", "s3cret")
        assertEquals(HttpStatusCode.BadRequest, loginStatus)
        assertEquals("login_unavailable", loginBody["code"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, client.get("/v1/info") { bearerAuth("change-me") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/info") { bearerAuth("nope") }.status)
    }

    @Test
    fun `no authentication mode needs no credentials`(): Unit = api(ServerConfig(insecureNoAuth = true)) { client ->
        val (authStatus, authBody) = client.json("/v1/auth")
        assertEquals(HttpStatusCode.OK, authStatus)
        assertEquals("none", authBody["mode"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, client.get("/v1/info").status)
        val (loginStatus, loginBody) = client.login("alice", "s3cret")
        assertEquals(HttpStatusCode.BadRequest, loginStatus)
        assertEquals("login_unavailable", loginBody["code"]!!.jsonPrimitive.content)
    }
}
