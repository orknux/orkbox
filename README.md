# orkbox

A sandbox service that gives coding agents a place to work. It is the companion
project to [orknux-server](https://github.com/michjak-szymanski/orknux-server).

orkbox exposes an HTTP **Session API**, and the same functionality as
a library that can be embedded in-process (orknux-server does this for local
coding). A session is an isolated
workspace: a filesystem plus a way to run commands in it. A caller creates a
session, reads and writes files, executes commands (`mvn test`, `npm run build`,
`make`, ...), reads their output and logs, and tears the session down when done.

## What it is not

orkbox **does not run agents and does not talk to any LLM provider.**
It holds no model credentials, no prompts and no agent loop. Deciding *what* to
do is orknux-server's job; orkbox only provides the *environment* in
which an agent's actions are carried out.

```
 orknux-server                   HTTP Session API     orkbox
┌──────────────────────────┐   or embedded    ┌───────────────────────────┐
│ agents, LLM providers,   │ ───────────────▶ │ sessions: files, commands │
│ workflows, credentials   │  (orkbox-core)   │ logs, lifecycle           │
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
- **Embeddable** — `orkbox-core` is a plain library (no HTTP server, no logging
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
  (`--runtime=runsc` / `kata-runtime`) and orkbox selects it through
  `ORKBOX_DOCKER_RUNTIME`. No separate code path is needed.
- **A native microVM runtime (Firecracker) is not worth it now.** It needs
  Linux with KVM (no Windows/macOS, often no nested virtualisation in cloud
  VMs), its own root filesystem images, a guest agent and network plumbing —
  roughly a second product. The runtime abstraction leaves room to add it later
  if multi-tenant hosting ever demands it.

## Modules

| module | what it is |
|---|---|
| `orkbox-core` | Sessions, executions, files, logs and the `docker` / `local` runtimes. Kotlin coroutines, kotlinx.serialization and slf4j-api only. |
| `orkbox-server` | The HTTP Session API on Ktor (CIO), packaged as a runnable jar or a GraalVM native image. |
| `images/toolchain` | `orknux/orkbox-toolchain`, the default container image. |

Containers are driven through the `docker` CLI rather than the Engine API.
That gives one code path for Docker Desktop on Windows (named pipe), Docker
Engine on Linux (unix socket), remote daemons (`DOCKER_HOST`) and Podman, and
nothing reflective for native images. The CLI has to be on the `PATH` of the
service.

## System requirements

The service itself is small; what a host needs is decided by the sessions it
runs. A default session may use 2 CPUs and 4 GB of memory, and the default
image is about 1.7 GB.

| | minimal | two sessions | recommended |
|---|---|---|---|
| CPU | 2 cores | 4 cores | 8 cores |
| memory | 4 GB | 8 GB | 16 GB |
| disk | 20 GB free | 30 GB free | 50 GB free, SSD |
| sessions working at once | 1 | 2 | 3–4 |
| container engine | Docker Engine, Docker Desktop or Podman | Docker Engine, Docker Desktop or Podman | Docker Engine on Linux |
| Java (jar only) | 25 | 25 | 25 |

Idle sessions take no place against `ORKBOX_MAX_SESSIONS` and little memory, so
the rows above count sessions working at once; each one open still keeps a
container and its workspace on disk. Session limits are caps, not reservations: a host can run more sessions than it
could hold at their limits, until they all build at once. On a minimal host
lower `ORKBOX_DOCKER_DEFAULT_MEMORY_MB` to 2048 so one session cannot take the
whole machine. Disk goes on the toolchain image, the shared dependency caches
(a Maven or npm cache reaches several GB) and each session's workspace. The
native image needs no Java; the `local` runtime needs no container engine.

## Running the server

```sh
docker build -t orknux/orkbox-toolchain:latest images/toolchain   # once
./mvnw package
ORKBOX_TOKEN=change-me java -jar orkbox-server/target/orkbox.jar
```

```sh
curl -s -H 'Authorization: Bearer change-me' -H 'Content-Type: application/json' \
     -d '{"caches":["maven"],"source":{"git":{"url":"https://github.com/octocat/Hello-World.git"}}}' \
     localhost:8080/v1/sessions
```

The OpenAPI 3.1 document is served at `/openapi.yaml` and browsable at
`/docs` (Swagger UI), both without a token. It is maintained by hand in
`orkbox-server/src/main/resources/openapi.yaml`; a test fails when it no
longer matches the routes or the model classes.

The server also has a built-in UI, served without a token at `/` and `/ui`:
the running sessions and their logs, a stop button for each, and a server
overview — uptime, host CPUs, memory, disk, JVM heap, and the CPU and memory
limits of every active session, backed by `GET /v1/system`. The page asks the
browser for the bearer token and sends it with every API call; when the
server runs with `ORKBOX_USER`/`ORKBOX_PASSWORD` it shows a user/password
form instead and signs in through `POST /v1/login`.

### Configuration

Environment variables (or system properties: `ORKBOX_MAX_SESSIONS` ->
`-Dorkbox.max.sessions`).

| variable | default | meaning |
|---|---|---|
| `ORKBOX_TOKEN` | — | Bearer token clients must send. Required unless `ORKBOX_USER` and `ORKBOX_PASSWORD` are set, or `ORKBOX_INSECURE_NO_AUTH`. |
| `ORKBOX_USER` / `ORKBOX_PASSWORD` | — | When both are set, clients can log in with `POST /v1/login` instead of knowing a token (the built-in UI's mode). |
| `ORKBOX_INSECURE_NO_AUTH` | `false` | Run without authentication (development only). |
| `ORKBOX_HOST` / `ORKBOX_PORT` | `0.0.0.0` / `8080` | Listen address. |
| `ORKBOX_RUNTIMES` | `docker` | Enabled runtimes, e.g. `docker,local`. |
| `ORKBOX_DEFAULT_RUNTIME` | first enabled | Runtime for requests that don't name one. |
| `ORKBOX_DEFAULT_TTL_SECONDS` / `ORKBOX_MAX_TTL_SECONDS` | `7200` / `86400` | Session lifetime. |
| `ORKBOX_DEFAULT_IDLE_TIMEOUT_SECONDS` | `900` | Idle sessions are stopped after this. |
| `ORKBOX_RETENTION_SECONDS` | `86400` | How long stopped sessions stay readable. |
| `ORKBOX_MAX_SESSIONS` | `50` | Sessions working at once - being created or running a command. Idle sessions, open with nothing running, do not count. |
| `ORKBOX_MAX_OUTPUT_BYTES` | `4194304` | Output kept per execution stream. |
| `ORKBOX_KILL_GRACE_SECONDS` | `10` | Between SIGTERM and SIGKILL on timeout. |
| `ORKBOX_MAX_PORTS` | `5` | Ports one session may have forwarded at once. |
| `ORKBOX_DOCKER_BINARY` | `docker` | CLI to use, e.g. `podman`. |
| `ORKBOX_DOCKER_DEFAULT_IMAGE` | `orknux/orkbox-toolchain:latest` | Image for sessions that don't name one. |
| `ORKBOX_DOCKER_IMAGES` | `*` | Comma-separated allowlist of images. |
| `ORKBOX_DOCKER_RUNTIME` | — | OCI runtime, e.g. `runsc` (gVisor) or `kata-runtime`. |
| `ORKBOX_DOCKER_DEFAULT_CPUS` / `_MAX_CPUS` | `2` / `8` | CPU limit. |
| `ORKBOX_DOCKER_DEFAULT_MEMORY_MB` / `_MAX_MEMORY_MB` | `4096` / `16384` | Memory limit (swap included). |
| `ORKBOX_DOCKER_DEFAULT_PIDS` / `_MAX_PIDS` | `1024` / `4096` | Process limit. |
| `ORKBOX_DOCKER_DISK_LIMIT` | `false` | Enforce `diskMb` (needs overlay2 on xfs with pquota). |
| `ORKBOX_DOCKER_CACHE_VOLUME_PREFIX` | `orkbox-cache-` | Named volumes for shared caches. |
| `ORKBOX_DOCKER_CLEANUP_ON_START` | `true` | Remove containers left by a previous run. |
| `ORKBOX_PUBLISH_HOST` | `0.0.0.0` | Address a forwarded port listens on; `127.0.0.1` keeps forwards to this machine. |
| `ORKBOX_LOCAL_ROOT` | `<tmp>/orkbox` | Where local workspaces live. |
| `ORKBOX_LOCAL_OWNER` | `orkbox` | Name written beside each local workspace; start-up clean-up removes only workspaces with this name, so instances sharing a root leave each other's alone. |
| `ORKBOX_LOCAL_SHELL` | `/bin/sh -lc`, or `cmd.exe /d /s /c` on Windows | Shell for `command` strings, e.g. `bash -lc` or `pwsh -NoProfile -Command`. |

Sessions live in memory: restarting the service stops them all (and, with the
default cleanup, removes their containers).

## Embedding

Depend on `orkbox-core`:

```xml
<dependency>
    <groupId>io.mszymanski.orknux</groupId>
    <artifactId>orkbox-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```kotlin
val orkbox = Orkbox(
    Config(
        runtimes = setOf(Config.RUNTIME_LOCAL),
        defaultRuntime = Config.RUNTIME_LOCAL,
        local = LocalConfig(root = Path.of("/var/lib/orknux/orkbox")),
    ),
).start()

val session = orkbox.sessions.create(CreateSessionRequest(labels = mapOf("workspace" to "acme")))
val run = orkbox.sessions.execute(session, ExecuteRequest(command = "git status"))
orkbox.sessions.await(session, run, seconds = 60)
println(run.toDto(maxOutputBytes = 65536).stdout)

orkbox.files.write(session, "notes.md", "hello".toByteArray(), mode = null, ifMatch = null)
orkbox.sessions.stop(session, "deleted")
orkbox.close()   // stops every session
```

`orkbox.sessions` covers lifecycle, executions and the event log;
`orkbox.files` the file operations. The request and response types are the
API's (`io.mszymanski.orknux.orkbox.model`), and failures are `ApiException`s
with the same `status` and `code` the HTTP API would return. The methods are
`suspend` functions. Settings can also come from the environment with
`Config.load()`.

## Building

Kotlin on JDK 25 with Maven.

```sh
./mvnw verify                                   # build and test (Docker tests skip without a daemon)
./mvnw -Pnative -pl orkbox-server -am package  # native image; needs GraalVM as JAVA_HOME
```

`ORKBOX_TEST_IMAGE` picks the image the Docker tests run against (default
`debian:bookworm-slim`, which has no git or ripgrep so the fallbacks get
covered).

## License

[Apache License 2.0](LICENSE)
