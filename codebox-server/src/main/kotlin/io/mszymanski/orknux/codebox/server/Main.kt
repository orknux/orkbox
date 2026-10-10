package io.mszymanski.orknux.codebox.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mszymanski.orknux.codebox.Codebox
import io.mszymanski.orknux.codebox.Config
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/** Settings of the standalone HTTP server, on top of the session [Config]. */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Shared bearer token. Null only when [insecureNoAuth] is set. */
    val token: String? = null,
    val insecureNoAuth: Boolean = false,
) {
    companion object {
        fun load(source: (String) -> String? = Config::lookup): ServerConfig {
            fun str(name: String) = source(name)?.trim()?.takeIf { it.isNotEmpty() }
            val config = ServerConfig(
                host = str("CODEBOX_HOST") ?: "0.0.0.0",
                port = str("CODEBOX_PORT")?.toInt() ?: 8080,
                token = str("CODEBOX_TOKEN"),
                insecureNoAuth = str("CODEBOX_INSECURE_NO_AUTH")?.toBooleanStrict() ?: false,
            )
            require(config.token != null || config.insecureNoAuth) {
                "CODEBOX_TOKEN is not set. Set it, or set CODEBOX_INSECURE_NO_AUTH=true to run without authentication."
            }
            return config
        }
    }
}

val version: String = Codebox::class.java.`package`?.implementationVersion ?: "dev"

/** Mounts the Session API for [codebox] on this application. */
fun Application.codebox(codebox: Codebox, server: ServerConfig) {
    installHttp()
    routes(codebox, server.token.takeUnless { server.insecureNoAuth }, version)
}

fun main() {
    val log = LoggerFactory.getLogger("orknux-codebox")
    val (config, server) = try {
        Config.load() to ServerConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error(e.message)
        exitProcess(2)
    }
    if (server.insecureNoAuth) log.warn("Authentication is disabled (CODEBOX_INSECURE_NO_AUTH=true)")
    val codebox = Codebox(config).start()
    log.info("orknux-codebox {} on {}:{}, runtimes {}", version, server.host, server.port, config.runtimes)
    embeddedServer(CIO, port = server.port, host = server.host) {
        codebox(codebox, server)
        monitor.subscribe(ApplicationStopping) { codebox.close() }
    }.start(wait = true)
}
