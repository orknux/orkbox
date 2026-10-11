package io.mszymanski.orknux.orkbox.runtime

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.DockerConfig
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.model.Resources
import io.mszymanski.orknux.orkbox.model.RuntimeInfo
import io.mszymanski.orknux.orkbox.model.SearchMatch
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * Sessions as ephemeral containers, driven through the `docker` CLI (or a
 * compatible one such as `podman`). Going through the CLI rather than the
 * Engine API keeps one code path for Docker Desktop on Windows, Docker Engine
 * and Podman on Linux, and for native images.
 *
 * Each container idles on `sleep infinity`; every command is a `docker exec`.
 */
class DockerRuntime(private val config: DockerConfig) : Runtime {
    override val name = Config.RUNTIME_DOCKER
    private val log = LoggerFactory.getLogger(DockerRuntime::class.java)

    companion object {
        const val WORKDIR = "/workspace"
        const val LABEL = "orknux.orkbox"
        const val SESSION_LABEL = "orknux.orkbox.session"

        /** Dependency caches a session can mount, and where the toolchains look for them. */
        val CACHES = mapOf(
            "maven" to "/root/.m2",
            "gradle" to "/root/.gradle",
            "npm" to "/root/.npm",
            "pnpm" to "/root/.local/share/pnpm/store",
            "yarn" to "/usr/local/share/.cache/yarn",
            "pip" to "/root/.cache/pip",
        )

        /** `command` strings run through bash when the image has it, sh otherwise. */
        val SHELL = listOf("bash", "-c")
    }

    internal val binary get() = config.binary

    suspend fun docker(vararg args: String, stdin: ByteArray? = null): Captured =
        Processes.run(listOf(config.binary) + args, stdin = stdin?.inputStream())

    fun defaults() = Resources(
        cpus = config.defaultCpus,
        memoryMb = config.defaultMemoryMb,
        diskMb = if (config.diskLimit) config.defaultDiskMb else null,
        pids = config.defaultPids,
    )

    fun limits() = Resources(
        cpus = config.maxCpus,
        memoryMb = config.maxMemoryMb,
        diskMb = if (config.diskLimit) config.maxDiskMb else null,
        pids = config.maxPids,
    )

    override suspend fun create(spec: SandboxSpec): Sandbox {
        val container = "orkbox-${spec.sessionId}"
        val resources = spec.resources ?: defaults()
        val args = mutableListOf(
            "run", "--detach", "--init",
            "--name", container,
            "--label", "$LABEL=true",
            "--label", "$SESSION_LABEL=${spec.sessionId}",
            "--hostname", "orkbox",
            "--workdir", WORKDIR,
        )
        resources.cpus?.let { args += listOf("--cpus", it.toString()) }
        resources.memoryMb?.let { args += listOf("--memory", "${it}m", "--memory-swap", "${it}m") }
        resources.pids?.let { args += listOf("--pids-limit", it.toString()) }
        if (config.diskLimit) resources.diskMb?.let { args += listOf("--storage-opt", "size=${it}m") }
        if (!spec.networkEnabled) args += listOf("--network", "none")
        config.containerRuntime?.let { args += listOf("--runtime", it) }
        for (cache in spec.caches) args += listOf("--volume", "${config.cacheVolumePrefix}$cache:${CACHES.getValue(cache)}")
        for ((key, value) in spec.env) args += listOf("--env", "$key=$value")
        // A random host port each, on the configured address: two sessions serving :3000 must not collide.
        for (port in spec.ports) args += listOf("--publish", "${config.publishHost}::$port")
        args += listOf(spec.image ?: config.defaultImage, "sleep", "infinity")

        val result = docker(*args.toTypedArray())
        if (!result.ok) {
            docker("rm", "--force", container)
            throw IllegalStateException("Container failed to start: ${result.err}")
        }
        val published = if (spec.ports.isEmpty()) emptyList() else published(container, spec.ports)
        return DockerSandbox(this, container, published)
    }

    /**
     * Where each of [ports] ended up, read back with `docker port`, whose lines
     * read `3000/tcp -> 127.0.0.1:49153` - twice, for IPv4 and IPv6, when
     * published on every interface; the first is kept.
     */
    private suspend fun published(container: String, ports: List<Int>): List<PublishedPort> {
        val lines = docker("port", container).out.lines()
        return ports.mapNotNull { port ->
            val line = lines.firstOrNull { it.startsWith("$port/tcp ") } ?: return@mapNotNull null
            val hostPort = line.substringAfter("->").trim().substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            PublishedPort(port, config.publishHost, hostPort)
        }
    }

    override suspend fun unavailableReason(): String? = try {
        val result = docker("version", "--format", "{{.Server.Version}}")
        if (result.ok) null else "Docker daemon not reachable: ${result.err}"
    } catch (e: ProgramNotFoundException) {
        e.message
    }

    override suspend fun cleanupOrphans() {
        if (!config.cleanupOnStart || unavailableReason() != null) return
        val ids = docker("ps", "--all", "--quiet", "--filter", "label=$LABEL=true").out.lines().filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        log.info("Removing {} container(s) left by a previous run", ids.size)
        docker("rm", "--force", *ids.toTypedArray())
    }

    override fun info(reason: String?) = RuntimeInfo(
        name = name,
        available = reason == null,
        unavailableReason = reason,
        workdir = WORKDIR,
        shell = SHELL,
        os = "linux",
        images = config.images.toList(),
        defaultImage = config.defaultImage,
        defaultResources = defaults(),
        maxResources = limits(),
        diskLimitEnforced = config.diskLimit,
        networkModes = listOf("full", "none"),
        caches = CACHES.keys.toList(),
    )
}

class DockerSandbox(
    private val runtime: DockerRuntime,
    private val container: String,
    override val ports: List<PublishedPort> = emptyList(),
) : Sandbox {
    override val workdir = DockerRuntime.WORKDIR

    private fun abs(path: String) = if (path.isEmpty()) workdir else "$workdir/$path"

    /** `docker exec` argv running [argv] in the container. */
    private fun exec(argv: List<String>, cwd: String = workdir, env: Map<String, String> = emptyMap()): List<String> =
        buildList {
            add(runtime.binary); add("exec"); add("--interactive"); add("--workdir"); add(cwd)
            for ((k, v) in env) { add("--env"); add("$k=$v") }
            add(container)
            addAll(argv)
        }

    override suspend fun spawn(spec: ProcessSpec): SandboxProcess = withContext(Dispatchers.IO) {
        val command = spec.argv ?: listOf(
            "sh", "-c", "if command -v bash >/dev/null 2>&1; then exec bash -c \"\$0\"; else exec sh -c \"\$0\"; fi", spec.command!!,
        )
        // A new session makes the command a process group we can signal as a whole;
        // its pid is recorded so /signal can find it later.
        val wrapped = listOf(
            "setsid", "-w", "sh", "-c",
            "mkdir -p /tmp/.orkbox && echo \$\$ > \"/tmp/.orkbox/\$0.pid\" && exec \"\$@\"",
            spec.executionId,
        ) + command
        val process = Processes.start(Processes.builder(exec(wrapped, abs(spec.cwd), spec.env)))
        SandboxProcess(process) { signal -> signal(spec.executionId, signal) }
    }

    private suspend fun signal(executionId: String, signal: Signal) {
        sh("p=\$(cat \"/tmp/.orkbox/\$2.pid\" 2>/dev/null) && kill -s \"\$1\" -- \"-\$p\"", signal.name, executionId)
    }

    override suspend fun run(argv: List<String>, stdin: ByteArray?): Captured =
        Processes.run(exec(argv), stdin = stdin?.inputStream())

    private suspend fun sh(script: String, vararg args: String, stdin: ByteArray? = null): Captured =
        run(listOf("sh", "-c", script, "sh") + args, stdin)

    /** Turns the conventional exit codes of the scripts below into API errors. */
    private fun check(result: Captured, path: String): Captured {
        when (result.exitCode) {
            0 -> return result
            2 -> throw ApiException.fileNotFound(path)
            17 -> throw ApiException.conflict("file_exists", "$path already exists; pass overwrite=true")
            20 -> throw ApiException.notDirectory(path)
            21 -> throw ApiException.isDirectory(path)
            39 -> throw ApiException.conflict("directory_not_empty", "$path is not empty; pass recursive=true")
            else -> throw IllegalStateException("Container operation on $path failed (${result.exitCode}): ${result.err}")
        }
    }

    override suspend fun readFile(path: String): ByteArray = check(
        sh("[ -d \"\$1\" ] && exit 21; [ -e \"\$1\" ] || exit 2; exec cat -- \"\$1\"", abs(path)), path,
    ).stdout

    override suspend fun writeFile(path: String, content: ByteArray, mode: String?) {
        val script = """
            [ -d "${'$'}1" ] && exit 21
            d=${'$'}(dirname -- "${'$'}1"); [ -e "${'$'}d" ] && [ ! -d "${'$'}d" ] && exit 20
            mkdir -p -- "${'$'}d" || exit 20
            cat > "${'$'}1" || exit 1
            [ -z "${'$'}2" ] || chmod "${'$'}2" -- "${'$'}1"
        """.trimIndent()
        val result = sh(script, abs(path), mode ?: "", stdin = content)
        check(result, if (result.exitCode == 20) WorkspacePath.parent(path) else path)
    }

    private val format = "%y\\0%s\\0%m\\0%T@\\0%l\\0%P\\0"

    private fun parseEntries(output: ByteArray, base: String, single: Boolean): List<FileEntry> {
        val fields = String(output, Charsets.UTF_8).split('\u0000')
        val entries = ArrayList<FileEntry>()
        var i = 0
        while (i + 5 < fields.size) {
            val (kind, size, mode, mtime, target) = fields.subList(i, i + 5)
            val name = fields[i + 5]
            val type = when (kind) { "f" -> "file"; "d" -> "directory"; "l" -> "symlink"; else -> "other" }
            val seconds = mtime.substringBefore('.').toLongOrNull() ?: 0
            val nanos = mtime.substringAfter('.', "0").padEnd(9, '0').take(9).toLongOrNull() ?: 0
            entries += FileEntry(
                path = if (single) base else WorkspacePath.join(base, name),
                type = type,
                size = if (type == "file") size.toLongOrNull() else null,
                mode = mode,
                modifiedAt = Instant.ofEpochSecond(seconds, nanos).toString(),
                target = target.takeIf { type == "symlink" },
            )
            i += 6
        }
        return entries
    }

    override suspend fun stat(path: String): FileEntry? {
        val result = sh("[ -e \"\$1\" ] || [ -L \"\$1\" ] || exit 2; exec find \"\$1\" -maxdepth 0 -printf '$format'", abs(path))
        if (result.exitCode == 2) return null
        return parseEntries(check(result, path).stdout, path, single = true).firstOrNull()
    }

    override suspend fun list(path: String, depth: Int?, includeHidden: Boolean): List<FileEntry> {
        val args = mutableListOf(abs(path), "-mindepth", "1")
        if (depth != null) args += listOf("-maxdepth", depth.toString())
        if (!includeHidden) args += listOf("(", "-name", ".*", "-prune", ")", "-o")
        args += listOf("-printf", format)
        val result = sh(
            "d=\$1; shift; [ -e \"\$d\" ] || exit 2; [ -d \"\$d\" ] || exit 20; exec find \"\$d\" \"\$@\"",
            *args.toTypedArray(),
        )
        return parseEntries(check(result, path).stdout, path, single = false).sortedBy { it.path }
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        if (path.isEmpty()) throw ApiException.badRequest("cannot_delete_root", "The workspace root cannot be deleted")
        val script = """
            [ -e "${'$'}1" ] || [ -L "${'$'}1" ] || exit 2
            if [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ]; then
              if [ "${'$'}2" = 1 ]; then rm -rf -- "${'$'}1"; else rmdir -- "${'$'}1" 2>/dev/null || exit 39; fi
            else rm -f -- "${'$'}1"; fi
        """.trimIndent()
        check(sh(script, abs(path), if (recursive) "1" else "0"), path)
    }

    override suspend fun move(from: String, to: String, overwrite: Boolean) {
        if (from.isEmpty() || to.isEmpty()) throw ApiException.badRequest("cannot_move_root", "The workspace root cannot be moved")
        val script = """
            [ -e "${'$'}1" ] || [ -L "${'$'}1" ] || exit 2
            if [ -e "${'$'}2" ] || [ -L "${'$'}2" ]; then
              [ "${'$'}3" = 1 ] || exit 17
              [ -d "${'$'}2" ] && [ ! -L "${'$'}2" ] && rm -rf -- "${'$'}2"
            fi
            mkdir -p -- "${'$'}(dirname -- "${'$'}2")" && mv -f -- "${'$'}1" "${'$'}2"
        """.trimIndent()
        val result = sh(script, abs(from), abs(to), if (overwrite) "1" else "0")
        check(result, if (result.exitCode == 17) to else from)
    }

    override suspend fun search(request: SearchRequest, path: String): SearchResult {
        if (stat(path) == null) throw ApiException.fileNotFound(path)
        val rg = run(Search.ripgrepArgs(request, path))
        // 126/127: no ripgrep in the image.
        if (rg.exitCode != 126 && rg.exitCode != 127) return Search.parseRipgrep(rg, request)
        return grep(request, path)
    }

    private suspend fun grep(request: SearchRequest, path: String): SearchResult {
        val args = mutableListOf("grep", "-rnIZ", if (request.regex) "-E" else "-F")
        if (!request.caseSensitive) args += "-i"
        request.glob?.let { args += "--include=${it.substringAfterLast('/')}" }
        args += listOf("-e", request.pattern, "--", path.ifEmpty { "." })
        val result = run(args)
        if (result.exitCode > 1) throw ApiException.badRequest("search_failed", result.err)
        val options = if (request.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = runCatching { Regex(if (request.regex) request.pattern else Regex.escape(request.pattern), options) }.getOrNull()
        val matches = ArrayList<SearchMatch>()
        var truncated = false
        for (line in result.out.lineSequence()) {
            val file = line.substringBefore('\u0000', "")
            val rest = line.substringAfter('\u0000', "")
            val number = rest.substringBefore(':').toIntOrNull() ?: continue
            if (matches.size >= request.maxResults) { truncated = true; break }
            val text = rest.substringAfter(':')
            val column = (pattern?.find(text)?.range?.first ?: 0) + 1
            matches += SearchMatch(file.removePrefix("./"), number, column, text)
        }
        return SearchResult(matches, truncated)
    }

    override suspend fun exportArchive(path: String, out: OutputStream) {
        val script = """
            [ -e "${'$'}1" ] || exit 2
            if [ -d "${'$'}1" ]; then exec tar -C "${'$'}1" -czf - .; else exec tar -C "${'$'}(dirname -- "${'$'}1")" -czf - "${'$'}(basename -- "${'$'}1")"; fi
        """.trimIndent()
        val result = Processes.run(exec(listOf("sh", "-c", script, "sh", abs(path))), stdout = out)
        check(result, path)
    }

    override suspend fun importArchive(path: String, input: InputStream, gzip: Boolean) {
        val script = "[ -e \"\$1\" ] && [ ! -d \"\$1\" ] && exit 20; mkdir -p -- \"\$1\" && exec tar -C \"\$1\" -x\$2f -"
        val result = Processes.run(exec(listOf("sh", "-c", script, "sh", abs(path), if (gzip) "z" else "")), stdin = input)
        if (result.exitCode == 20) throw ApiException.notDirectory(path)
        if (!result.ok) throw ApiException.badRequest("archive_invalid", "tar failed: ${result.err}")
    }

    override suspend fun usage(): Usage? {
        val script = """
            echo "cpu=${'$'}(awk '/^usage_usec/ {print ${'$'}2}' /sys/fs/cgroup/cpu.stat 2>/dev/null)"
            echo "peak=${'$'}(cat /sys/fs/cgroup/memory.peak 2>/dev/null)"
            echo "current=${'$'}(cat /sys/fs/cgroup/memory.current 2>/dev/null)"
        """.trimIndent()
        val result = runCatching { sh(script) }.getOrNull()?.takeIf { it.ok } ?: return null
        val values = result.out.lines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim().toLongOrNull() }
        val mb = 1024L * 1024
        return Usage(
            cpuSeconds = values["cpu"]?.let { it / 1_000_000.0 },
            memoryPeakMb = values["peak"]?.let { it / mb },
            memoryCurrentMb = values["current"]?.let { it / mb },
        )
    }

    override suspend fun destroy() {
        runtime.docker("rm", "--force", "--volumes", container)
    }
}
