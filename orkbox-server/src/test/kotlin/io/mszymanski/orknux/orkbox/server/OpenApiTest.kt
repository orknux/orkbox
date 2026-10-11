package io.mszymanski.orknux.orkbox.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mszymanski.orknux.orkbox.Orkbox
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.LocalConfig
import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.DirectoryListing
import io.mszymanski.orknux.orkbox.model.Edit
import io.mszymanski.orknux.orkbox.model.ForwardRequest
import io.mszymanski.orknux.orkbox.model.PortForwards
import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.EditResult
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.ExecutionCounts
import io.mszymanski.orknux.orkbox.model.ExecutionDto
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.GitSource
import io.mszymanski.orknux.orkbox.model.HostInfo
import io.mszymanski.orknux.orkbox.model.Info
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.Resources
import io.mszymanski.orknux.orkbox.model.RuntimeInfo
import io.mszymanski.orknux.orkbox.model.SearchMatch
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.SessionDto
import io.mszymanski.orknux.orkbox.model.SignalRequest
import io.mszymanski.orknux.orkbox.model.Source
import io.mszymanski.orknux.orkbox.model.SystemInfo
import io.mszymanski.orknux.orkbox.model.SystemSessions
import io.mszymanski.orknux.orkbox.model.UpdateSessionRequest
import io.mszymanski.orknux.orkbox.model.Usage
import io.mszymanski.orknux.orkbox.model.WriteResult
import io.mszymanski.orknux.orkbox.session.Event
import kotlinx.serialization.KSerializer
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keeps the hand-written OpenAPI document in step with the code. */
class OpenApiTest {
    @Suppress("UNCHECKED_CAST")
    private val spec = Yaml().load<Map<String, Any>>(OpenApi.document("test")) as Map<String, Map<String, Any>>

    @Test
    fun `every route is documented and every documented operation exists`() = testApplication {
        val orkbox = Orkbox(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("orkbox-openapi-test")),
        ))
        var routes = emptySet<String>()
        application {
            orkbox(orkbox, ServerConfig(token = "t"))
            routes = routing { }.getAllRoutes().mapNotNull { route ->
                val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
                "${method.value} ${path(route)}"
            }.toSet()
        }
        startApplication()

        val documented = spec.getValue("paths").flatMap { (path, item) ->
            @Suppress("UNCHECKED_CAST")
            (item as Map<String, Any>).keys.filter { it != "parameters" }.map { "${it.uppercase()} $path" }
        }.toSet()

        assertEquals(emptySet(), routes - documented, "routes missing from openapi.yaml")
        assertEquals(emptySet(), documented - routes, "documented operations with no route")
        orkbox.close()
    }

    private fun path(route: RoutingNode): String =
        generateSequence(route) { it.parent }.toList().reversed()
            .map { it.selector.toString() }
            .filter { it.isNotEmpty() && !it.startsWith("(") && it != "/" && it != "<slash>" }
            .joinToString("/", prefix = "/")

    @Test
    fun `schemas have exactly the fields of the model classes`() {
        val models: Map<String, KSerializer<*>> = mapOf(
            "Resources" to Resources.serializer(),
            "GitSource" to GitSource.serializer(),
            "Source" to Source.serializer(),
            "CreateSessionRequest" to CreateSessionRequest.serializer(),
            "UpdateSessionRequest" to UpdateSessionRequest.serializer(),
            "ExecutionCounts" to ExecutionCounts.serializer(),
            "Usage" to Usage.serializer(),
            "Session" to SessionDto.serializer(),
            "PublishedPort" to PublishedPort.serializer(),
            "ForwardRequest" to ForwardRequest.serializer(),
            "PortForwards" to PortForwards.serializer(),
            "ExecuteRequest" to ExecuteRequest.serializer(),
            "Execution" to ExecutionDto.serializer(),
            "SignalRequest" to SignalRequest.serializer(),
            "OutputChunk" to OutputChunk.serializer(),
            "Event" to Event.serializer(),
            "FileEntry" to FileEntry.serializer(),
            "DirectoryListing" to DirectoryListing.serializer(),
            "WriteResult" to WriteResult.serializer(),
            "Edit" to Edit.serializer(),
            "EditRequest" to EditRequest.serializer(),
            "EditResult" to EditResult.serializer(),
            "MoveRequest" to MoveRequest.serializer(),
            "SearchRequest" to SearchRequest.serializer(),
            "SearchMatch" to SearchMatch.serializer(),
            "SearchResult" to SearchResult.serializer(),
            "RuntimeInfo" to RuntimeInfo.serializer(),
            "Info" to Info.serializer(),
            "HostInfo" to HostInfo.serializer(),
            "SystemSessions" to SystemSessions.serializer(),
            "SystemInfo" to SystemInfo.serializer(),
        )
        @Suppress("UNCHECKED_CAST")
        val schemas = spec.getValue("components")["schemas"] as Map<String, Map<String, Any>>
        for ((name, serializer) in models) {
            val schema = schemas[name] ?: error("schema $name is missing")
            @Suppress("UNCHECKED_CAST")
            val properties = (schema["properties"] as Map<String, Any>).keys
            val fields = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }.toSet()
            assertEquals(fields, properties, "schema $name")
        }
    }

    @Test
    fun `the document and the UI are served without a token`() = testApplication {
        val orkbox = Orkbox(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("orkbox-openapi-test")),
        ))
        application { orkbox(orkbox, ServerConfig(token = "t")) }
        val yaml = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, yaml.status)
        val text = yaml.bodyAsText()
        assertTrue(text.startsWith("openapi: 3.1.0"))
        assertFalse("__VERSION__" in text)
        assertTrue("swagger-ui" in client.get("/docs").bodyAsText())
        for (path in listOf("/", "/ui")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue("text/html" in (page.headers[HttpHeaders.ContentType] ?: ""), path)
        }
        assertTrue("orkbox" in client.get("/ui").bodyAsText())
        orkbox.close()
    }
}
