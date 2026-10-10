package io.mszymanski.orknux.orkbox.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mszymanski.orknux.orkbox.Orkbox
import io.mszymanski.orknux.orkbox.Config
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
                host = str("ORKBOX_HOST") ?: "0.0.0.0",
                port = str("ORKBOX_PORT")?.toInt() ?: 8080,
                token = str("ORKBOX_TOKEN"),
                insecureNoAuth = str("ORKBOX_INSECURE_NO_AUTH")?.toBooleanStrict() ?: false,
            )
            require(config.token != null || config.insecureNoAuth) {
                "ORKBOX_TOKEN is not set. Set it, or set ORKBOX_INSECURE_NO_AUTH=true to run without authentication."
            }
            return config
        }
    }
}

val version: String = Orkbox::class.java.`package`?.implementationVersion ?: "dev"

/** Mounts the Session API for [orkbox] on this application. */
fun Application.orkbox(orkbox: Orkbox, server: ServerConfig) {
    installHttp()
    routes(orkbox, server.token.takeUnless { server.insecureNoAuth }, version)
}

fun main() {
    val log = LoggerFactory.getLogger("orkbox")
    val (config, server) = try {
        Config.load() to ServerConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error(e.message)
        exitProcess(2)
    }
    if (server.insecureNoAuth) log.warn("Authentication is disabled (ORKBOX_INSECURE_NO_AUTH=true)")
    val orkbox = Orkbox(config).start()
    log.info("orkbox {} on {}:{}, runtimes {}", version, server.host, server.port, config.runtimes)
    embeddedServer(CIO, port = server.port, host = server.host) {
        orkbox(orkbox, server)
        monitor.subscribe(ApplicationStopping) { orkbox.close() }
    }.start(wait = true)
}
