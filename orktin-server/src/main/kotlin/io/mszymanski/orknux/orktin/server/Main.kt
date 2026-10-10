package io.mszymanski.orknux.orktin.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mszymanski.orknux.orktin.Orktin
import io.mszymanski.orknux.orktin.Config
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
                host = str("ORKTIN_HOST") ?: "0.0.0.0",
                port = str("ORKTIN_PORT")?.toInt() ?: 8080,
                token = str("ORKTIN_TOKEN"),
                insecureNoAuth = str("ORKTIN_INSECURE_NO_AUTH")?.toBooleanStrict() ?: false,
            )
            require(config.token != null || config.insecureNoAuth) {
                "ORKTIN_TOKEN is not set. Set it, or set ORKTIN_INSECURE_NO_AUTH=true to run without authentication."
            }
            return config
        }
    }
}

val version: String = Orktin::class.java.`package`?.implementationVersion ?: "dev"

/** Mounts the Session API for [orktin] on this application. */
fun Application.orktin(orktin: Orktin, server: ServerConfig) {
    installHttp()
    routes(orktin, server.token.takeUnless { server.insecureNoAuth }, version)
}

fun main() {
    val log = LoggerFactory.getLogger("orktin")
    val (config, server) = try {
        Config.load() to ServerConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error(e.message)
        exitProcess(2)
    }
    if (server.insecureNoAuth) log.warn("Authentication is disabled (ORKTIN_INSECURE_NO_AUTH=true)")
    val orktin = Orktin(config).start()
    log.info("orktin {} on {}:{}, runtimes {}", version, server.host, server.port, config.runtimes)
    embeddedServer(CIO, port = server.port, host = server.host) {
        orktin(orktin, server)
        monitor.subscribe(ApplicationStopping) { orktin.close() }
    }.start(wait = true)
}
