package io.mszymanski.orknux.orktin

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Session configuration. Embedders build it directly; the standalone server
 * reads it with [load] from `ORKTIN_*` environment variables.
 *
 * Every variable is also accepted as a JVM system property with the same name
 * in lower case and dots (`ORKTIN_MAX_SESSIONS` -> `orktin.max.sessions`),
 * which is handier in tests and IDE run configurations.
 */
data class Config(
    val runtimes: Set<String> = setOf(RUNTIME_DOCKER),
    val defaultRuntime: String = RUNTIME_DOCKER,

    val defaultTtlSeconds: Long = 2 * 3600,
    val maxTtlSeconds: Long = 24 * 3600,
    val defaultIdleTimeoutSeconds: Long = 15 * 60,
    /** How long a stopped session's record and logs stay readable. */
    val retentionSeconds: Long = 24 * 3600,
    val maxSessions: Int = 50,

    /** Output kept per execution stream; beyond it only the tail is retained. */
    val maxOutputBytes: Int = 4 * 1024 * 1024,
    val maxEvents: Int = 20_000,
    val killGraceSeconds: Long = 10,

    val docker: DockerConfig = DockerConfig(),
    val local: LocalConfig = LocalConfig(),
) {
    companion object {
        const val RUNTIME_DOCKER = "docker"
        const val RUNTIME_LOCAL = "local"

        fun load(source: (String) -> String? = ::lookup): Config {
            fun str(name: String) = source(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun long(name: String, default: Long) = str(name)?.toLong() ?: default
            fun int(name: String, default: Int) = str(name)?.toInt() ?: default
            fun bool(name: String) = str(name)?.toBooleanStrict() ?: false
            fun list(name: String) = str(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

            val runtimes = list("ORKTIN_RUNTIMES")?.toSet() ?: setOf(RUNTIME_DOCKER)
            val config = Config(
                runtimes = runtimes,
                defaultRuntime = str("ORKTIN_DEFAULT_RUNTIME") ?: runtimes.first(),
                defaultTtlSeconds = long("ORKTIN_DEFAULT_TTL_SECONDS", 2 * 3600),
                maxTtlSeconds = long("ORKTIN_MAX_TTL_SECONDS", 24 * 3600),
                defaultIdleTimeoutSeconds = long("ORKTIN_DEFAULT_IDLE_TIMEOUT_SECONDS", 15 * 60),
                retentionSeconds = long("ORKTIN_RETENTION_SECONDS", 24 * 3600),
                maxSessions = int("ORKTIN_MAX_SESSIONS", 50),
                maxOutputBytes = int("ORKTIN_MAX_OUTPUT_BYTES", 4 * 1024 * 1024),
                maxEvents = int("ORKTIN_MAX_EVENTS", 20_000),
                killGraceSeconds = long("ORKTIN_KILL_GRACE_SECONDS", 10),
                docker = DockerConfig(
                    binary = str("ORKTIN_DOCKER_BINARY") ?: "docker",
                    defaultImage = str("ORKTIN_DOCKER_DEFAULT_IMAGE") ?: DockerConfig.DEFAULT_IMAGE,
                    images = list("ORKTIN_DOCKER_IMAGES")?.toSet() ?: setOf("*"),
                    containerRuntime = str("ORKTIN_DOCKER_RUNTIME"),
                    defaultCpus = str("ORKTIN_DOCKER_DEFAULT_CPUS")?.toDouble() ?: 2.0,
                    maxCpus = str("ORKTIN_DOCKER_MAX_CPUS")?.toDouble() ?: 8.0,
                    defaultMemoryMb = long("ORKTIN_DOCKER_DEFAULT_MEMORY_MB", 4096),
                    maxMemoryMb = long("ORKTIN_DOCKER_MAX_MEMORY_MB", 16384),
                    defaultPids = long("ORKTIN_DOCKER_DEFAULT_PIDS", 1024),
                    maxPids = long("ORKTIN_DOCKER_MAX_PIDS", 4096),
                    diskLimit = bool("ORKTIN_DOCKER_DISK_LIMIT"),
                    defaultDiskMb = long("ORKTIN_DOCKER_DEFAULT_DISK_MB", 10240),
                    maxDiskMb = long("ORKTIN_DOCKER_MAX_DISK_MB", 51200),
                    cacheVolumePrefix = str("ORKTIN_DOCKER_CACHE_VOLUME_PREFIX") ?: "orktin-cache-",
                    cleanupOnStart = str("ORKTIN_DOCKER_CLEANUP_ON_START")?.toBooleanStrict() ?: true,
                ),
                local = LocalConfig(
                    root = Path(str("ORKTIN_LOCAL_ROOT") ?: Path(System.getProperty("java.io.tmpdir"), "orktin").toString()),
                    shell = str("ORKTIN_LOCAL_SHELL")?.split(' ')?.filter { it.isNotEmpty() } ?: LocalConfig.defaultShell(),
                ),
            )
            config.validate()
            return config
        }

        fun lookup(name: String): String? =
            System.getProperty(name.lowercase().replace('_', '.')) ?: System.getenv(name)
    }

    fun validate() {
        require(runtimes.isNotEmpty()) { "ORKTIN_RUNTIMES must name at least one runtime" }
        val unknown = runtimes - setOf(RUNTIME_DOCKER, RUNTIME_LOCAL)
        require(unknown.isEmpty()) { "Unknown runtimes in ORKTIN_RUNTIMES: $unknown" }
        require(defaultRuntime in runtimes) { "ORKTIN_DEFAULT_RUNTIME '$defaultRuntime' is not enabled" }
    }
}

data class DockerConfig(
    /** The CLI to drive containers with; `podman` works as well. */
    val binary: String = "docker",
    val defaultImage: String = DEFAULT_IMAGE,
    /** Images a session may ask for. `*` allows any image. */
    val images: Set<String> = setOf("*"),
    /** OCI runtime passed as `--runtime`, e.g. `runsc` (gVisor) or `kata-runtime`. */
    val containerRuntime: String? = null,
    val defaultCpus: Double = 2.0,
    val maxCpus: Double = 8.0,
    val defaultMemoryMb: Long = 4096,
    val maxMemoryMb: Long = 16384,
    val defaultPids: Long = 1024,
    val maxPids: Long = 4096,
    /** Disk quotas need overlay2 on xfs with pquota, so they are opt-in. */
    val diskLimit: Boolean = false,
    val defaultDiskMb: Long = 10240,
    val maxDiskMb: Long = 51200,
    val cacheVolumePrefix: String = "orktin-cache-",
    /** Remove containers left behind by a previous run of the service. */
    val cleanupOnStart: Boolean = true,
) {
    fun allows(image: String) = "*" in images || image in images

    companion object {
        const val DEFAULT_IMAGE = "orknux/orktin-toolchain:latest"
    }
}

data class LocalConfig(
    /** Directory under which every local session gets its workspace. */
    val root: Path = Path(System.getProperty("java.io.tmpdir"), "orktin"),
    /** Command prefix a `command` string is appended to. */
    val shell: List<String> = defaultShell(),
) {
    companion object {
        val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

        fun defaultShell(): List<String> =
            if (isWindows) listOf("cmd.exe", "/d", "/s", "/c") else listOf("/bin/sh", "-lc")
    }
}
