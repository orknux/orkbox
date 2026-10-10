package io.mszymanski.orknux.codebox.server

/**
 * The OpenAPI document (`openapi.yaml` on the classpath) and a Swagger UI
 * page for it. The document is written by hand; `OpenApiTest` keeps it in
 * step with the routes and the model classes.
 */
object OpenApi {
    const val RESOURCE = "/openapi.yaml"

    fun document(version: String): String {
        val text = OpenApi::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$RESOURCE is missing from the classpath")
        return text.replace("__VERSION__", version)
    }

    val SWAGGER_UI = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>orknux-codebox API</title>
          <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css">
        </head>
        <body>
          <div id="ui"></div>
          <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js"></script>
          <script>
            SwaggerUIBundle({ url: "openapi.yaml", dom_id: "#ui", persistAuthorization: true });
          </script>
        </body>
        </html>
    """.trimIndent()
}
