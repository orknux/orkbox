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
    /** Shared bearer token clients must send. */
    val token: String? = null,
    /** When set with [password], clients can `POST /v1/login` instead of knowing [token]. */
    val user: String? = null,
    val password: String? = null,
    val insecureNoAuth: Boolean = false,
) {
    companion object {
        fun load(source: (String) -> String? = Config::lookup): ServerConfig {
            fun str(name: String) = source(name)?.trim()?.takeIf { it.isNotEmpty() }
            val config = ServerConfig(
                host = str("ORKBOX_HOST") ?: "0.0.0.0",
                port = str("ORKBOX_PORT")?.toInt() ?: 8080,
                token = str("ORKBOX_TOKEN"),
                user = str("ORKBOX_USER"),
                password = str("ORKBOX_PASSWORD"),
                insecureNoAuth = str("ORKBOX_INSECURE_NO_AUTH")?.toBooleanStrict() ?: false,
            )
            config.validate()
            return config
        }
    }

    /** How the API is authenticated; resolved once, shared by the routes and the UI. */
    val auth: Auth = when {
        insecureNoAuth -> Auth.None
        token != null -> Auth.Token(token)
        user != null && password != null -> Auth.UserPass(user, password)
        else -> Auth.None
    }

    fun validate() {
        require((user == null) == (password == null)) {
            "ORKBOX_USER and ORKBOX_PASSWORD must be set together."
        }
        val hasUserPass = user != null && password != null
        require(token != null || hasUserPass || insecureNoAuth) {
            "No authentication configured. Set ORKBOX_TOKEN, or ORKBOX_USER and ORKBOX_PASSWORD, or ORKBOX_INSECURE_NO_AUTH=true to run without authentication."
        }
    }
}

val version: String = Orkbox::class.java.`package`?.implementationVersion ?: "dev"

/** Mounts the Session API for [orkbox] on this application. */
fun Application.orkbox(orkbox: Orkbox, server: ServerConfig) {
    installHttp()
    routes(orkbox, server.auth, version)
}

fun main() {
    val log = LoggerFactory.getLogger("orkbox")
    val (config, server) = try {
        Config.load() to ServerConfig.load()
    } catch (e: IllegalArgumentException) {
        log.error(e.message)
        exitProcess(2)
    }
    val orkbox = Orkbox(config).start()
    log.info("orkbox {} on {}:{}, runtimes {}", version, server.host, server.port, config.runtimes)
    when (server.auth) {
        is Auth.None -> log.warn("Authentication is disabled (ORKBOX_INSECURE_NO_AUTH=true)")
        is Auth.Token -> log.info("authentication: bearer token")
        is Auth.UserPass -> log.info("authentication: user/password (login at /v1/login)")
    }
    embeddedServer(CIO, port = server.port, host = server.host) {
        orkbox(orkbox, server)
        monitor.subscribe(ApplicationStopping) { orkbox.close() }
    }.start(wait = true)
}
