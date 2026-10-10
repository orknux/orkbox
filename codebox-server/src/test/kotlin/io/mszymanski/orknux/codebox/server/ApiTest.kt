package io.mszymanski.orknux.codebox.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mszymanski.orknux.codebox.Codebox
import io.mszymanski.orknux.codebox.Config
import io.mszymanski.orknux.codebox.LocalConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val token = "test-token"
    private val windows = LocalConfig.isWindows

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val codebox = Codebox(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("codebox-api-test")),
        ))
        application { codebox(codebox, ServerConfig(token = token)) }
        try {
            block(client)
        } finally {
            codebox.close()
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private val JsonObject.str get() = { key: String -> this[key]!!.jsonPrimitive.content }

    private suspend fun HttpClient.postJson(path: String, body: String) = post(path) {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `requests need the bearer token`() = api { client ->
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val denied = client.get("/v1/info")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertEquals("application/problem+json", denied.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("unauthorized", denied.json().str("code"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/info") { bearerAuth("wrong") }.status)

        val info = client.get("/v1/info") { bearerAuth(token) }.json()
        assertEquals("local", info.str("defaultRuntime"))
        assertEquals(true, info["runtimes"]!!.jsonArray[0].jsonObject["available"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a session can be driven end to end`() = api { client ->
        val created = client.postJson("/v1/sessions", """{"labels":{"workspace":"acme"},"env":{"GREETING":"hi"}}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val session = created.json()
        val id = session.str("id")
        assertEquals("ready", session.str("status"))
        assertEquals("/v1/sessions/$id", created.headers[HttpHeaders.Location])

        // Files
        val put = client.put("/v1/sessions/$id/files/content?path=app/main.txt") { bearerAuth(token); setBody("hello world\n") }
        assertEquals(HttpStatusCode.OK, put.status)
        val read = client.get("/v1/sessions/$id/files/content?path=app/main.txt") { bearerAuth(token) }
        assertEquals("hello world\n", read.bodyAsText())
        assertEquals(put.headers[HttpHeaders.ETag], read.headers[HttpHeaders.ETag])
        assertEquals("1", read.headers["X-Line-Count"])

        val conflict = client.put("/v1/sessions/$id/files/content?path=app/main.txt") {
            bearerAuth(token); header(HttpHeaders.IfMatch, "\"stale\""); setBody("x")
        }
        assertEquals(HttpStatusCode.PreconditionFailed, conflict.status)

        val edit = client.postJson("/v1/sessions/$id/files/edit", """{"path":"app/main.txt","edits":[{"oldText":"world","newText":"codebox"}]}""")
        assertEquals(HttpStatusCode.OK, edit.status, edit.bodyAsText())
        val noMatch = client.postJson("/v1/sessions/$id/files/edit", """{"path":"app/main.txt","edits":[{"oldText":"nope","newText":"x"}]}""")
        assertEquals(422, noMatch.status.value)
        assertEquals(0, noMatch.json()["editIndex"]!!.jsonPrimitive.int)

        val listing = client.get("/v1/sessions/$id/files?path=app") { bearerAuth(token) }.json()
        assertEquals("app/main.txt", listing["entries"]!!.jsonArray[0].jsonObject.str("path"))

        val search = client.postJson("/v1/sessions/$id/files/search", """{"pattern":"codebox"}""").json()
        assertEquals("app/main.txt", search["matches"]!!.jsonArray[0].jsonObject.str("path"))

        val outside = client.get("/v1/sessions/$id/files/content?path=../../x") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, outside.status)
        assertEquals("path_outside_workspace", outside.json().str("code"))

        // Executions
        val printGreeting = if (windows) "echo %GREETING%" else "echo \$GREETING"
        val exec = client.postJson("/v1/sessions/$id/executions", Json.encodeToString(JsonObject.serializer(),
            JsonObject(mapOf("command" to kotlinx.serialization.json.JsonPrimitive(printGreeting)))))
        assertEquals(HttpStatusCode.OK, exec.status, exec.bodyAsText())
        val execution = exec.json()
        assertEquals("succeeded", execution.str("status"))
        assertEquals("hi", execution.str("stdout").trim())

        val background = client.postJson("/v1/sessions/$id/executions", """{"command":"echo later","wait":false}""").json()
        val execId = background.str("id")
        val finished = client.get("/v1/sessions/$id/executions/$execId?wait=true&waitSeconds=30") { bearerAuth(token) }.json()
        assertEquals("succeeded", finished.str("status"))
        val output = client.get("/v1/sessions/$id/executions/$execId/output") { bearerAuth(token) }
        assertEquals("later", output.bodyAsText().trim())
        assertEquals("succeeded", output.headers["X-Execution-Status"])

        val followed = client.get("/v1/sessions/$id/executions/$execId/output?follow=true") { bearerAuth(token) }.bodyAsText()
        assertContains(followed, "event: output")
        assertContains(followed, "event: end")

        val bad = client.postJson("/v1/sessions/$id/executions", """{"command":"x","argv":["y"]}""")
        assertEquals("invalid_command", bad.json().str("code"))

        val executions = client.get("/v1/sessions/$id/executions") { bearerAuth(token) }.json()
        assertTrue(executions["items"]!!.jsonArray.size >= 2)

        // Archive
        val archive = client.get("/v1/sessions/$id/archive?path=app") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, archive.status)
        val bytes = archive.bodyAsBytes()
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        val upload = client.put("/v1/sessions/$id/archive?path=copy") { bearerAuth(token); setBody(bytes) }
        assertEquals(HttpStatusCode.NoContent, upload.status, upload.bodyAsText())
        assertEquals("hello codebox\n", client.get("/v1/sessions/$id/files/content?path=copy/main.txt") { bearerAuth(token) }.bodyAsText())

        // Listing, updating, logs
        val filtered = client.get("/v1/sessions?label=workspace:acme") { bearerAuth(token) }.json()
        assertEquals(id, filtered["items"]!!.jsonArray.single().jsonObject.str("id"))
        assertEquals(0, client.get("/v1/sessions?label=workspace:other") { bearerAuth(token) }.json()["items"]!!.jsonArray.size)

        val patched = client.patch("/v1/sessions/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"labels":{"workspace":null,"run":"r1"}}""")
        }.json()
        assertEquals(mapOf("run" to "r1"), patched["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })

        val logs = client.get("/v1/sessions/$id/logs") { bearerAuth(token) }.json()
        val types = logs["items"]!!.jsonArray.map { it.jsonObject.str("type") }
        assertTrue("session.created" in types && "file.written" in types && "execution.finished" in types, types.toString())
        assertTrue("execution.output" !in types)

        // Teardown
        val deleted = client.delete("/v1/sessions/$id") { bearerAuth(token) }.json()
        assertEquals("stopped", deleted.str("status"))
        assertEquals("deleted", deleted.str("stopReason"))
        val gone = client.postJson("/v1/sessions/$id/executions", """{"command":"echo"}""")
        assertEquals(HttpStatusCode.Conflict, gone.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sessions/ses_missing") { bearerAuth(token) }.status)
    }

    @Test
    fun `invalid requests are problems`() = api { client ->
        val malformed = client.postJson("/v1/sessions", "{not json")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("invalid_request", malformed.json().str("code"))
        val runtime = client.postJson("/v1/sessions", """{"runtime":"docker"}""")
        assertEquals("runtime_not_enabled", runtime.json().str("code"))
    }
}
