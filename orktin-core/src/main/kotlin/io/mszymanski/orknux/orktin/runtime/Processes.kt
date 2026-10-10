package io.mszymanski.orknux.orktin.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Output of a short-lived helper process run to completion. */
class Captured(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray) {
    val out: String get() = String(stdout, Charsets.UTF_8)
    val err: String get() = String(stderr, Charsets.UTF_8).trim()
    val ok: Boolean get() = exitCode == 0
}

/** Thrown when the program itself cannot be started (not installed, not on PATH). */
class ProgramNotFoundException(program: String, cause: Throwable) : IOException("Cannot run $program: ${cause.message}", cause)

object Processes {
    /** Environment variables of this service that must never reach a session. */
    private fun isPrivate(name: String) = name.uppercase().startsWith("ORKTIN_")

    private val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

    fun builder(argv: List<String>, cwd: File? = null, env: Map<String, String> = emptyMap()): ProcessBuilder {
        val builder = ProcessBuilder(if (isWindows) windowsArgv(argv) else argv)
        if (cwd != null) builder.directory(cwd)
        val environment = builder.environment()
        environment.keys.removeIf(::isPrivate)
        environment.putAll(env)
        return builder
    }

    /**
     * On Windows a process gets one command line, which the JDK assembles
     * without escaping embedded quotes. Quoting each argument the way
     * `CommandLineToArgvW` (and Go, and the MSVC runtime) parse it makes
     * arguments such as shell scripts arrive intact; the JDK leaves arguments
     * that are already quoted alone. `cmd.exe` has its own rules and expects
     * its command verbatim, so it is left to the JDK.
     */
    internal fun windowsArgv(argv: List<String>): List<String> {
        val program = argv.first().substringAfterLast('\\').substringAfterLast('/').lowercase()
        if (program == "cmd" || program == "cmd.exe") return argv
        return listOf(argv.first()) + argv.drop(1).map(::quoteWindowsArg)
    }

    internal fun quoteWindowsArg(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
        val out = StringBuilder("\"")
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> backslashes++
                '"' -> { out.append("\\".repeat(backslashes * 2 + 1)).append('"'); backslashes = 0 }
                else -> { out.append("\\".repeat(backslashes)).append(c); backslashes = 0 }
            }
        }
        out.append("\\".repeat(backslashes * 2)).append('"')
        return out.toString()
    }

    fun start(builder: ProcessBuilder): Process = try {
        builder.start()
    } catch (e: IOException) {
        throw ProgramNotFoundException(builder.command().first(), e)
    }

    /**
     * Runs [argv] to completion. [stdin] is written and closed; stdout goes to
     * [stdout] when given, otherwise it is captured. A cancelled caller kills
     * the process.
     */
    suspend fun run(
        argv: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        stdin: InputStream? = null,
        stdout: OutputStream? = null,
    ): Captured = withContext(Dispatchers.IO) {
        val process = start(builder(argv, cwd, env))
        // Blocking stream reads ignore interrupts; killing the process is what unblocks them.
        coroutineContext.job.invokeOnCompletion { if (it != null) process.destroyForcibly() }
        try {
            coroutineScope {
                launch {
                    runInterruptible {
                        try {
                            process.outputStream.use { out -> stdin?.copyTo(out) }
                        } catch (_: IOException) {
                            // The process exited without reading all of its input.
                        }
                    }
                }
                val err = async { runInterruptible { process.errorStream.readAllBytes() } }
                val out = async {
                    runInterruptible {
                        if (stdout == null) process.inputStream.readAllBytes()
                        else {
                            process.inputStream.copyTo(stdout); ByteArray(0)
                        }
                    }
                }
                val code = runInterruptible { process.waitFor() }
                Captured(code, out.await(), err.await())
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    suspend fun run(argv: List<String>, stdin: ByteArray, cwd: File? = null): Captured =
        run(argv, cwd, stdin = stdin.inputStream())
}
