package io.mszymanski.orknux.codebox

import io.mszymanski.orknux.codebox.runtime.DockerRuntime
import io.mszymanski.orknux.codebox.runtime.LocalRuntime
import io.mszymanski.orknux.codebox.runtime.Runtime
import io.mszymanski.orknux.codebox.session.FileService
import io.mszymanski.orknux.codebox.session.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * The embeddable entry point: everything the HTTP API offers, as a library.
 *
 * ```kotlin
 * val codebox = Codebox(Config(runtimes = setOf("local"), defaultRuntime = "local")).start()
 * val session = codebox.sessions.create(CreateSessionRequest())
 * val run = codebox.sessions.execute(session, ExecuteRequest(command = "git --version"))
 * codebox.sessions.await(session, run, 60)
 * codebox.close()
 * ```
 *
 * [sessions] covers lifecycle, executions and logs; [files] the file
 * operations. Errors are [ApiException]s carrying the same codes as the API.
 */
class Codebox(
    val config: Config,
    runtimes: Map<String, Runtime> = defaultRuntimes(config),
) : AutoCloseable {
    init {
        config.validate()
    }

    val sessions = SessionManager(config, runtimes)
    val files = FileService()

    /** Starts the reaper and removes sandboxes left behind by a previous run. */
    fun start(): Codebox {
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
