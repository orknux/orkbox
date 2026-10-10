# orknux-codebox

A sandbox service that gives coding agents a place to work. It is the companion
project to [orknux-server](https://github.com/michjak-szymanski/orknux-server).

orknux-codebox exposes an HTTP **Session API**. A session is an isolated
workspace: a filesystem plus a way to run commands in it. A caller creates a
session, reads and writes files, executes commands (`mvn test`, `npm run build`,
`make`, ...), reads their output and logs, and tears the session down when done.

## What it is not

orknux-codebox **does not run agents and does not talk to any LLM provider.**
It holds no model credentials, no prompts and no agent loop. Deciding *what* to
do is orknux-server's job; orknux-codebox only provides the *environment* in
which an agent's actions are carried out.

```
 orknux-server                          orknux-codebox
┌──────────────────────────┐   HTTP    ┌───────────────────────────┐
│ agents, LLM providers,   │ ────────▶ │ sessions: files, commands │
│ workflows, credentials   │  Session  │ logs, lifecycle           │
└──────────────────────────┘    API    └─────────────┬─────────────┘
                                                     │
                                       ┌─────────────┴─────────────┐
                                       ▼                           ▼
                               ephemeral container           local system
                                  (Docker)                  (trusted, dev)
```

## Goals

- **Session API over HTTP** — create a session, inspect it, read its logs,
  execute commands in it, work with its files, and delete it. The full API is
  proposed in [docs/session-api.md](docs/session-api.md).
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
  the `docker` runtime only talks to the Docker Engine API, an operator who
  wants stronger isolation configures gVisor or Kata as the Docker runtime
  (`--runtime=runsc` / `kata-runtime`) and orknux-codebox selects it per
  configuration. No separate code path is needed.
- **A native microVM runtime (Firecracker) is not worth it now.** It needs
  Linux with KVM (no Windows/macOS, often no nested virtualisation in cloud
  VMs), its own root filesystem images, a guest agent and network plumbing —
  roughly a second product. The runtime abstraction leaves room to add it later
  if multi-tenant hosting ever demands it.

## Building

> Not implemented yet. Planned stack: Kotlin on JDK 25, Maven, Ktor (CIO) with
> kotlinx.serialization, and the GraalVM Native Build Tools plugin. The Docker
> runtime talks to the Docker Engine API directly over its socket rather than
> through a reflection-heavy client library, to keep native builds simple.

```sh
./mvnw package             # JVM jar
./mvnw -Pnative package    # GraalVM native image
```

## License

[Apache License 2.0](LICENSE)
