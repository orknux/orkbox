# orktin

A sandbox service that gives coding agents a place to work. It is the companion
project to [orknux-server](https://github.com/michjak-szymanski/orknux-server).

orktin exposes an HTTP **Session API**, and the same functionality as
a library that can be embedded in-process (orknux-server does this for local
coding). A session is an isolated
workspace: a filesystem plus a way to run commands in it. A caller creates a
session, reads and writes files, executes commands (`mvn test`, `npm run build`,
`make`, ...), reads their output and logs, and tears the session down when done.

## What it is not

orktin **does not run agents and does not talk to any LLM provider.**
It holds no model credentials, no prompts and no agent loop. Deciding *what* to
do is orknux-server's job; orktin only provides the *environment* in
which an agent's actions are carried out.

```
 orknux-server                   HTTP Session API     orktin
┌──────────────────────────┐   or embedded    ┌───────────────────────────┐
│ agents, LLM providers,   │ ───────────────▶ │ sessions: files, commands │
│ workflows, credentials   │  (orktin-core)   │ logs, lifecycle           │
└──────────────────────────┘                  └─────────────┬─────────────┘
                                                            │
                                              ┌─────────────┴─────────────┐
                                              ▼                           ▼
                                      ephemeral container           local system
                                         (Docker)                  (trusted, dev)
```

## Goals

- **Session API over HTTP** — create a session, inspect it, read its logs,
  execute commands in it, work with its files, and delete it. The full API is
  specified in [docs/session-api.md](docs/session-api.md).
- **Embeddable** — `orktin-core` is a plain library (no HTTP server, no logging
  backend) offering the same operations in-process.
- **Windows and Linux hosts** — the service and both runtimes run on either;
  Docker Desktop and Docker Engine are both supported, as is Podman.
- **Pluggable session runtimes**
  - **`docker`** — every session is an ephemeral container, destroyed with the
    session. This is the default and the only runtime meant for untrusted work.
  - **`local`** — the session is a directory on the host and commands run as
    the service's own user. No isolation; for development and trusted setups
    only, and disabled unless explicitly enabled.
- **Real builds out of the box** — the default container image can build
  Maven, Gradle, Node.js, C and C++ projects without extra setup (JDK, Maven,
  Gradle, Node.js with npm/pnpm/yarn, gcc/g++, clang, make, CMake, Ninja, git,
  plus the usual shell tools). Dependency caches (`~/.m2`, `~/.gradle`,
  `~/.npm`, ...) can be shared between sessions so builds don't re-download the
  internet each time.
- **Bounded sessions** — CPU, memory, disk and process limits, an optional
  no-network mode, a time-to-live and an idle timeout, after which a session is
  reaped automatically.
- **JVM or native** — a Kotlin application on the latest JDK that builds either
  to a regular JVM jar or to a GraalVM native image.

## Containers or virtual machines?

The question is how strongly one session has to be isolated from the host and
from other sessions, and how much it costs to get there.

| option | isolation | start time | requires | fit |
|---|---|---|---|---|
| Docker (`runc`) | namespaces + cgroups, shared kernel | ~1 s | Docker / Podman | default |
| Docker + [gVisor](https://gvisor.dev) (`runsc`) | user-space kernel intercepts syscalls | ~1 s | Linux | hardened option, no code changes |
| Docker + [Kata Containers](https://katacontainers.io) | lightweight VM per container | 1–3 s | Linux + KVM | VM isolation, no code changes |
| [Firecracker](https://firecracker-microvm.github.io) / Cloud Hypervisor microVMs | full VM per session | ~1 s | Linux + KVM, own images, networking, agent in guest | possible future runtime |
| QEMU/libvirt full VMs | full VM | 10 s+ | KVM, heavy images | too slow and heavy |

All of the above are free and open source. The conclusion:

- **Containers are the right default.** They start fast, toolchain images are
  just Dockerfiles, Docker runs everywhere (including Docker Desktop on
  Windows and macOS for development), and Maven/Gradle/Node/C/C++ builds work
  in them without any trouble.
- **VM-grade isolation comes for free through the container runtime.** Because
  the `docker` runtime only drives the `docker` CLI, an operator who
  wants stronger isolation configures gVisor or Kata as the Docker runtime
  (`--runtime=runsc` / `kata-runtime`) and orktin selects it through
  `ORKTIN_DOCKER_RUNTIME`. No separate code path is needed.
- **A native microVM runtime (Firecracker) is not worth it now.** It needs
  Linux with KVM (no Windows/macOS, often no nested virtualisation in cloud
  VMs), its own root filesystem images, a guest agent and network plumbing —
  roughly a second product. The runtime abstraction leaves room to add it later
  if multi-tenant hosting ever demands it.

## Modules

| module | what it is |
|---|---|
| `orktin-core` | Sessions, executions, files, logs and the `docker` / `local` runtimes. Kotlin coroutines, kotlinx.serialization and slf4j-api only. |
| `orktin-server` | The HTTP Session API on Ktor (CIO), packaged as a runnable jar or a GraalVM native image. |
| `images/toolchain` | `orknux/orktin-toolchain`, the default container image. |

Containers are driven through the `docker` CLI rather than the Engine API.
That gives one code path for Docker Desktop on Windows (named pipe), Docker
Engine on Linux (unix socket), remote daemons (`DOCKER_HOST`) and Podman, and
nothing reflective for native images. The CLI has to be on the `PATH` of the
service.

## Running the server

```sh
docker build -t orknux/orktin-toolchain:latest images/toolchain   # once
./mvnw package
ORKTIN_TOKEN=change-me java -jar orktin-server/target/orktin.jar
```

```sh
curl -s -H 'Authorization: Bearer change-me' -H 'Content-Type: application/json' \
     -d '{"caches":["maven"],"source":{"git":{"url":"https://github.com/octocat/Hello-World.git"}}}' \
     localhost:8080/v1/sessions
```

The OpenAPI 3.1 document is served at `/openapi.yaml` and browsable at
`/docs` (Swagger UI), both without a token. It is maintained by hand in
`orktin-server/src/main/resources/openapi.yaml`; a test fails when it no
longer matches the routes or the model classes.

### Configuration

Environment variables (or system properties: `ORKTIN_MAX_SESSIONS` ->
`-Dorktin.max.sessions`).

| variable | default | meaning |
|---|---|---|
| `ORKTIN_TOKEN` | — | Bearer token clients must send. Required. |
| `ORKTIN_INSECURE_NO_AUTH` | `false` | Run without a token (development only). |
| `ORKTIN_HOST` / `ORKTIN_PORT` | `0.0.0.0` / `8080` | Listen address. |
| `ORKTIN_RUNTIMES` | `docker` | Enabled runtimes, e.g. `docker,local`. |
| `ORKTIN_DEFAULT_RUNTIME` | first enabled | Runtime for requests that don't name one. |
| `ORKTIN_DEFAULT_TTL_SECONDS` / `ORKTIN_MAX_TTL_SECONDS` | `7200` / `86400` | Session lifetime. |
| `ORKTIN_DEFAULT_IDLE_TIMEOUT_SECONDS` | `900` | Idle sessions are stopped after this. |
| `ORKTIN_RETENTION_SECONDS` | `86400` | How long stopped sessions stay readable. |
| `ORKTIN_MAX_SESSIONS` | `50` | Active sessions at once. |
| `ORKTIN_MAX_OUTPUT_BYTES` | `4194304` | Output kept per execution stream. |
| `ORKTIN_KILL_GRACE_SECONDS` | `10` | Between SIGTERM and SIGKILL on timeout. |
| `ORKTIN_DOCKER_BINARY` | `docker` | CLI to use, e.g. `podman`. |
| `ORKTIN_DOCKER_DEFAULT_IMAGE` | `orknux/orktin-toolchain:latest` | Image for sessions that don't name one. |
| `ORKTIN_DOCKER_IMAGES` | `*` | Comma-separated allowlist of images. |
| `ORKTIN_DOCKER_RUNTIME` | — | OCI runtime, e.g. `runsc` (gVisor) or `kata-runtime`. |
| `ORKTIN_DOCKER_DEFAULT_CPUS` / `_MAX_CPUS` | `2` / `8` | CPU limit. |
| `ORKTIN_DOCKER_DEFAULT_MEMORY_MB` / `_MAX_MEMORY_MB` | `4096` / `16384` | Memory limit (swap included). |
| `ORKTIN_DOCKER_DEFAULT_PIDS` / `_MAX_PIDS` | `1024` / `4096` | Process limit. |
| `ORKTIN_DOCKER_DISK_LIMIT` | `false` | Enforce `diskMb` (needs overlay2 on xfs with pquota). |
| `ORKTIN_DOCKER_CACHE_VOLUME_PREFIX` | `orktin-cache-` | Named volumes for shared caches. |
| `ORKTIN_DOCKER_CLEANUP_ON_START` | `true` | Remove containers left by a previous run. |
| `ORKTIN_LOCAL_ROOT` | `<tmp>/orktin` | Where local workspaces live. |
| `ORKTIN_LOCAL_SHELL` | `/bin/sh -lc`, or `cmd.exe /d /s /c` on Windows | Shell for `command` strings, e.g. `bash -lc` or `pwsh -NoProfile -Command`. |

Sessions live in memory: restarting the service stops them all (and, with the
default cleanup, removes their containers).

## Embedding

Depend on `orktin-core`:

```xml
<dependency>
    <groupId>io.mszymanski.orknux</groupId>
    <artifactId>orktin-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```kotlin
val orktin = Orktin(
    Config(
        runtimes = setOf(Config.RUNTIME_LOCAL),
        defaultRuntime = Config.RUNTIME_LOCAL,
        local = LocalConfig(root = Path.of("/var/lib/orknux/orktin")),
    ),
).start()

val session = orktin.sessions.create(CreateSessionRequest(labels = mapOf("workspace" to "acme")))
val run = orktin.sessions.execute(session, ExecuteRequest(command = "git status"))
orktin.sessions.await(session, run, seconds = 60)
println(run.toDto(maxOutputBytes = 65536).stdout)

orktin.files.write(session, "notes.md", "hello".toByteArray(), mode = null, ifMatch = null)
orktin.sessions.stop(session, "deleted")
orktin.close()   // stops every session
```

`orktin.sessions` covers lifecycle, executions and the event log;
`orktin.files` the file operations. The request and response types are the
API's (`io.mszymanski.orknux.orktin.model`), and failures are `ApiException`s
with the same `status` and `code` the HTTP API would return. The methods are
`suspend` functions. Settings can also come from the environment with
`Config.load()`.

## Building

Kotlin on JDK 25 with Maven.

```sh
./mvnw verify                                   # build and test (Docker tests skip without a daemon)
./mvnw -Pnative -pl orktin-server -am package  # native image; needs GraalVM as JAVA_HOME
```

`ORKTIN_TEST_IMAGE` picks the image the Docker tests run against (default
`debian:bookworm-slim`, which has no git or ripgrep so the fallbacks get
covered).

## License

[Apache License 2.0](LICENSE)
