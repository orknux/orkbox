package io.mszymanski.orknux.orktin.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mszymanski.orknux.orktin.Orktin
import io.mszymanski.orknux.orktin.Config
import io.mszymanski.orknux.orktin.LocalConfig
import io.mszymanski.orknux.orktin.model.CreateSessionRequest
import io.mszymanski.orknux.orktin.model.DirectoryListing
import io.mszymanski.orknux.orktin.model.Edit
import io.mszymanski.orknux.orktin.model.EditRequest
import io.mszymanski.orknux.orktin.model.EditResult
import io.mszymanski.orknux.orktin.model.ExecuteRequest
import io.mszymanski.orknux.orktin.model.ExecutionCounts
import io.mszymanski.orknux.orktin.model.ExecutionDto
import io.mszymanski.orknux.orktin.model.FileEntry
import io.mszymanski.orknux.orktin.model.GitSource
import io.mszymanski.orknux.orktin.model.Info
import io.mszymanski.orknux.orktin.model.MoveRequest
import io.mszymanski.orknux.orktin.model.Resources
import io.mszymanski.orknux.orktin.model.RuntimeInfo
import io.mszymanski.orknux.orktin.model.SearchMatch
import io.mszymanski.orknux.orktin.model.SearchRequest
import io.mszymanski.orknux.orktin.model.SearchResult
import io.mszymanski.orknux.orktin.model.SessionDto
import io.mszymanski.orknux.orktin.model.SignalRequest
import io.mszymanski.orknux.orktin.model.Source
import io.mszymanski.orknux.orktin.model.UpdateSessionRequest
import io.mszymanski.orknux.orktin.model.Usage
import io.mszymanski.orknux.orktin.model.WriteResult
import io.mszymanski.orknux.orktin.session.Event
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
        val orktin = Orktin(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("orktin-openapi-test")),
        ))
        var routes = emptySet<String>()
        application {
            orktin(orktin, ServerConfig(token = "t"))
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
        orktin.close()
    }

    private fun path(route: RoutingNode): String =
        generateSequence(route) { it.parent }.toList().reversed()
            .map { it.selector.toString() }
            .filter { it.isNotEmpty() && !it.startsWith("(") && it != "/" }
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
        val orktin = Orktin(Config(
            runtimes = setOf(Config.RUNTIME_LOCAL),
            defaultRuntime = Config.RUNTIME_LOCAL,
            local = LocalConfig(root = Files.createTempDirectory("orktin-openapi-test")),
        ))
        application { orktin(orktin, ServerConfig(token = "t")) }
        val yaml = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, yaml.status)
        val text = yaml.bodyAsText()
        assertTrue(text.startsWith("openapi: 3.1.0"))
        assertFalse("__VERSION__" in text)
        assertTrue("swagger-ui" in client.get("/docs").bodyAsText())
        orktin.close()
    }
}
