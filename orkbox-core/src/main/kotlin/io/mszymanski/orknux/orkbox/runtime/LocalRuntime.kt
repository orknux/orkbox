package io.mszymanski.orknux.orkbox.runtime

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.LocalConfig
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.RuntimeInfo
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Sessions as directories on the host, with commands run as the service's own
 * user. There is no isolation: anything a command does, the service could do.
 */
/** The file beside a workspace naming the instance that made it. */
private const val OWNER_SUFFIX = ".owner"

class LocalRuntime(private val config: LocalConfig) : Runtime {
    override val name = Config.RUNTIME_LOCAL
    private val log = LoggerFactory.getLogger(LocalRuntime::class.java)

    override suspend fun create(spec: SandboxSpec): Sandbox = withContext(Dispatchers.IO) {
        val dir = config.root.resolve(spec.sessionId)
        dir.createDirectories()
        /* Beside the workspace rather than in it, so the session never sees it. */
        Files.writeString(config.root.resolve(spec.sessionId + OWNER_SUFFIX), config.owner)
        LocalSandbox(dir.toRealPath(), spec.env, config.shell)
    }

    override suspend fun unavailableReason(): String? = withContext(Dispatchers.IO) {
        runCatching { config.root.createDirectories() }.exceptionOrNull()?.let { "Cannot create ${config.root}: ${it.message}" }
    }

    override suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        if (!config.root.exists()) return@withContext
        Files.list(config.root).use { entries ->
            /*
             * Only what this instance made: a workspace whose owner file names
             * another - or that has none - may belong to a different server
             * sharing this root, and deleting it pulled the floor from under
             * that server's live sessions.
             */
            entries.filter { it.name.startsWith("ses_") && it.isDirectory() }.forEach {
                val marker = config.root.resolve(it.name + OWNER_SUFFIX)
                val owner = runCatching { Files.readString(marker).trim() }.getOrNull()
                if (owner != config.owner) return@forEach
                log.info("Removing workspace {} left by a previous run", it)
                deleteTree(it)
                Files.deleteIfExists(marker)
            }
        }
    }

    override fun info(reason: String?) = RuntimeInfo(
        name = name,
        available = reason == null,
        unavailableReason = reason,
        workdir = config.root.toString(),
        shell = config.shell,
        os = System.getProperty("os.name"),
        networkModes = listOf("full"),
        caches = emptyList(),
    )
}

class LocalSandbox(
    private val root: Path,
    private val env: Map<String, String>,
    private val shell: List<String>,
) : Sandbox {
    override val workdir: String = root.toString()

    /**
     * A local session's commands run on this machine, so its port is this
     * machine's: the connection goes to it directly. A forward then only moves
     * a server that listens on loopback to the configured address.
     */
    override suspend fun connect(port: Int): Tunnel = withContext(Dispatchers.IO) {
        val socket = java.net.Socket("127.0.0.1", port)
        Tunnel(socket.getInputStream(), socket.getOutputStream(), { socket.shutdownOutput() }, { socket.close() })
    }

    /** Resolves a normalised path, refusing symlinks that lead out of the workspace. */
    private fun resolve(path: String): Path {
        /* Without this, a workspace removed from under the session read as every path being outside it. */
        if (!Files.exists(root)) throw ApiException.workspaceMissing()
        val target = if (path.isEmpty()) root else root.resolve(path)
        var existing = target
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: break
        if (Files.exists(existing) && !existing.toRealPath().startsWith(root)) throw ApiException.pathOutsideWorkspace(path)
        return target
    }

    override suspend fun spawn(spec: ProcessSpec): SandboxProcess = withContext(Dispatchers.IO) {
        val argv = spec.argv ?: (shell + spec.command!!)
        val cwd = resolve(spec.cwd)
        if (!cwd.isDirectory()) throw ApiException.notDirectory(spec.cwd.ifEmpty { "." })
        val process = Processes.start(Processes.builder(argv, cwd.toFile(), env + spec.env))
        SandboxProcess(process) { signal -> signal(process, signal) }
    }

    private suspend fun signal(process: Process, signal: Signal) = withContext(Dispatchers.IO) {
        val handle = process.toHandle()
        val tree = handle.descendants().toList().reversed() + handle
        if (!LocalConfig.isWindows && signal != Signal.KILL) {
            // Interrupts and hangups need a real signal; the JDK only sends TERM and KILL.
            tree.forEach { p -> runCatching { Processes.run(listOf("kill", "-s", signal.name, p.pid().toString())) } }
        } else {
            tree.forEach { p -> if (signal == Signal.KILL) p.destroyForcibly() else p.destroy() }
        }
    }

    override suspend fun run(argv: List<String>, stdin: ByteArray?): Captured =
        Processes.run(argv, root.toFile(), env, stdin?.inputStream())

    override suspend fun readFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file.isDirectory()) throw ApiException.isDirectory(path)
        try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            throw ApiException.fileNotFound(path)
        }
    }

    override suspend fun writeFile(path: String, content: ByteArray, mode: String?) = withContext(Dispatchers.IO) {
        if (path.isEmpty()) throw ApiException.isDirectory("/")
        val file = resolve(path)
        if (file.isDirectory()) throw ApiException.isDirectory(path)
        try {
            file.parent.createDirectories()
        } catch (_: FileAlreadyExistsException) {
            throw ApiException.notDirectory(WorkspacePath.parent(path))
        }
        Files.write(file, content)
        if (mode != null) setMode(file, mode)
    }

    private fun setMode(file: Path, mode: String) {
        if (LocalConfig.isWindows) return
        val bits = mode.toInt(8)
        val symbolic = buildString {
            for (shift in listOf(6, 3, 0)) {
                val b = (bits shr shift) and 7
                append(if (b and 4 != 0) 'r' else '-')
                append(if (b and 2 != 0) 'w' else '-')
                append(if (b and 1 != 0) 'x' else '-')
            }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(symbolic))
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) null else entry(file, path)
    }

    private fun entry(file: Path, path: String): FileEntry {
        val link = Files.isSymbolicLink(file)
        val dir = !link && Files.isDirectory(file)
        val mode = if (LocalConfig.isWindows) null else runCatching {
            Integer.toOctalString(Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int and 0x1ff)
        }.getOrNull()
        return FileEntry(
            path = path,
            type = when {
                link -> "symlink"
                dir -> "directory"
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> "file"
                else -> "other"
            },
            size = if (!dir && !link) Files.size(file) else null,
            mode = mode,
            modifiedAt = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().toString(),
            target = if (link) Files.readSymbolicLink(file).toString().replace('\\', '/') else null,
        )
    }

    override suspend fun list(path: String, depth: Int?, includeHidden: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (!Files.exists(dir)) throw ApiException.fileNotFound(path)
        if (!dir.isDirectory()) throw ApiException.notDirectory(path)
        val result = ArrayList<FileEntry>()
        fun visit(current: Path, relative: String, level: Int) {
            Files.list(current).use { it.toList() }.sorted().forEach { child ->
                if (!includeHidden && child.name.startsWith('.')) return@forEach
                val childPath = WorkspacePath.join(relative, child.name)
                result += entry(child, childPath)
                if ((depth == null || level < depth) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    visit(child, childPath, level + 1)
                }
            }
        }
        visit(dir, path, 1)
        result
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        if (path.isEmpty()) throw ApiException.badRequest("cannot_delete_root", "The workspace root cannot be deleted")
        val file = resolve(path)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw ApiException.fileNotFound(path)
        if (recursive && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) deleteTreeOrSay(file, path)
        else try {
            Files.delete(file)
        } catch (_: DirectoryNotEmptyException) {
            throw ApiException.conflict("directory_not_empty", "$path is not empty; pass recursive=true")
        }
    }

    override suspend fun move(from: String, to: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        if (from.isEmpty() || to.isEmpty()) throw ApiException.badRequest("cannot_move_root", "The workspace root cannot be moved")
        val source = resolve(from)
        val target = resolve(to)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw ApiException.fileNotFound(from)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) throw ApiException.conflict("file_exists", "$to already exists; pass overwrite=true")
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) deleteTreeOrSay(target, to)
        }
        target.parent.createDirectories()
        val options = if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        Files.move(source, target, *options)
        Unit
    }

    override suspend fun search(request: SearchRequest, path: String): SearchResult {
        val start = resolve(path)
        if (!Files.exists(start)) throw ApiException.fileNotFound(path)
        return try {
            Search.parseRipgrep(run(Search.ripgrepArgs(request, path)), request)
        } catch (_: ProgramNotFoundException) {
            withContext(Dispatchers.IO) { Search.walk(root, start, request) }
        }
    }

    override suspend fun exportArchive(path: String, out: OutputStream) {
        val target = resolve(path)
        if (!Files.exists(target)) throw ApiException.fileNotFound(path)
        val (dir, member) = if (target.isDirectory()) target to "." else target.parent to target.name
        val result = Processes.run(listOf("tar", "-C", dir.toString(), "-czf", "-", member), stdout = out)
        if (!result.ok) throw IllegalStateException("tar failed: ${result.err}")
    }

    override suspend fun importArchive(path: String, input: InputStream, gzip: Boolean) {
        val dir = resolve(path)
        withContext(Dispatchers.IO) {
            if (Files.exists(dir) && !dir.isDirectory()) throw ApiException.notDirectory(path)
            dir.createDirectories()
        }
        val result = Processes.run(listOf("tar", "-C", dir.toString(), if (gzip) "-xzf" else "-xf", "-"), stdin = input)
        if (!result.ok) throw ApiException.badRequest("archive_invalid", "tar failed: ${result.err}")
    }

    override suspend fun usage(): Usage? = null

    override suspend fun destroy() = withContext(Dispatchers.IO) {
        deleteTree(root)
        Files.deleteIfExists(root.resolveSibling(root.fileName.toString() + OWNER_SUFFIX))
        Unit
    }
}

/**
 * [deleteTree] for a caller that was asked to delete something and must say
 * whether it did. On Windows a file a running process holds open cannot be
 * deleted, and the best-effort walk skipped it silently - so a delete of a
 * build directory whose jar was still running answered "deleted" and left the
 * directory there.
 */
internal fun deleteTreeOrSay(path: Path, asked: String) {
    deleteTree(path)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    val left = Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.limit(1000).toList() }
    val example = left.firstOrNull()?.let { path.relativize(it).toString().replace('\\', '/') }
    throw ApiException.conflict(
        "delete_incomplete",
        "$asked was not fully deleted: ${left.size} file(s) remain" +
            (example?.let { ", e.g. $it" } ?: "") +
            ". A running process may be holding them open - stop it and delete again.",
    )
}

/** Deletes what it can and skips the rest: right for cleaning up, wrong for answering a request. */
internal fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { p ->
            runCatching {
                // Read-only files (git objects on Windows) refuse deletion until made writable.
                p.toFile().setWritable(true)
                Files.deleteIfExists(p)
            }
        }
    }
}
