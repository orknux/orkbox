package io.mszymanski.orknux.orktin

import io.mszymanski.orknux.orktin.runtime.DockerRuntime
import io.mszymanski.orknux.orktin.runtime.LocalRuntime
import io.mszymanski.orknux.orktin.runtime.Runtime
import io.mszymanski.orknux.orktin.session.FileService
import io.mszymanski.orknux.orktin.session.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * The embeddable entry point: everything the HTTP API offers, as a library.
 *
 * ```kotlin
 * val orktin = Orktin(Config(runtimes = setOf("local"), defaultRuntime = "local")).start()
 * val session = orktin.sessions.create(CreateSessionRequest())
 * val run = orktin.sessions.execute(session, ExecuteRequest(command = "git --version"))
 * orktin.sessions.await(session, run, 60)
 * orktin.close()
 * ```
 *
 * [sessions] covers lifecycle, executions and logs; [files] the file
 * operations. Errors are [ApiException]s carrying the same codes as the API.
 */
class Orktin(
    val config: Config,
    runtimes: Map<String, Runtime> = defaultRuntimes(config),
) : AutoCloseable {
    init {
        config.validate()
    }

    val sessions = SessionManager(config, runtimes)
    val files = FileService()

    /** Starts the reaper and removes sandboxes left behind by a previous run. */
    fun start(): Orktin {
        sessions.start()
        return this
    }

    /** Stops every active session. */
    override fun close() = runBlocking { sessions.shutdown() }

    companion object {
        fun defaultRuntimes(config: Config): Map<String, Runtime> = config.runtimes.associateWith { name ->
            when (name) {
                Config.RUNTIME_DOCKER -> DockerRuntime(config.docker)
                Config.RUNTIME_LOCAL -> LocalRuntime(config.local)
                else -> throw IllegalArgumentException("Unknown runtime $name")
            }
        }
    }
}
