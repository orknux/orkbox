# AGENTS.md

Guidance for coding agents working in this repository. Read the
[README](README.md) for what orktin is and [docs/session-api.md](docs/session-api.md)
for the API it serves.

## What this project is

A sandbox service for coding agents: sessions with a workspace filesystem and
a way to run commands in it, behind an HTTP **Session API** (`orktin-server`)
or embedded in-process as a library (`orktin-core`). orktin runs no agents and
talks to no LLM provider — don't add model clients, prompts or agent logic.

## Layout

| path | contents |
|---|---|
| `orktin-core` | Library: `Orktin` facade, `Config`, `model/` (API request/response types), `session/` (sessions, executions, files, event log), `runtime/` (`docker` and `local` runtimes). |
| `orktin-server` | Ktor (CIO) HTTP server: `Routes.kt`, `Http.kt`, `Main.kt`, `OpenApi.kt`, and the hand-written `src/main/resources/openapi.yaml`. |
| `images/toolchain` | Dockerfile for `orknux/orktin-toolchain`, the default session image. |
| `docs/session-api.md` | The API specification. |

Package root: `io.mszymanski.orknux.orktin`.

## Build and test

Kotlin on JDK 25, built with the Maven wrapper.

```sh
./mvnw verify                                   # build and run all tests
./mvnw -pl orktin-core test                     # one module
./mvnw -pl orktin-core test -Dtest=LocalSessionTest
./mvnw -Pnative -pl orktin-server -am package  # native image (GraalVM as JAVA_HOME)
```

- Docker-backed tests (`DockerSessionTest`) skip themselves when no daemon is
  reachable; `ORKTIN_TEST_IMAGE` picks their image (default
  `debian:bookworm-slim`, deliberately without git/ripgrep so fallbacks get
  covered).
- Run `./mvnw verify` before considering a change done.

## Rules

- **Keep `orktin-core` lean.** Its only dependencies are Kotlin stdlib,
  coroutines, kotlinx.serialization and `slf4j-api`. No HTTP server, no logging
  backend, nothing reflective.
- **Drive containers through the `docker` CLI**, never the Engine API. That is
  what makes Docker Desktop, Docker Engine, remote daemons and Podman share one
  code path, and keeps native images free of reflection.
- **Both runtimes must work on Windows and Linux hosts.** Mind shells
  (`cmd.exe` vs `/bin/sh`), path separators and line endings. Scripts and
  Dockerfiles must stay LF (see `.gitattributes`).
- **Runtime behaviour is specified by contract tests.** `SessionContract` is the
  abstract suite; `LocalSessionTest` and `DockerSessionTest` run it per runtime.
  New session behaviour goes into the contract so both runtimes are covered.
- **API changes touch four places together:** the model classes, the routes,
  `openapi.yaml`, and `docs/session-api.md`. `OpenApiTest` fails when the
  OpenAPI document drifts from the routes or model classes.
- **Errors are `ApiException`s** carrying the same `status` and stable `code`
  as the HTTP problem+json response; reuse existing codes where they fit.
- **Paths from callers are untrusted.** Resolve them through `WorkspacePath.normalize`
  so nothing escapes the workspace (`path_outside_workspace`).
- **Native image:** resources the server loads at runtime must be listed in
  `orktin-server/src/main/resources/META-INF/native-image/.../resource-config.json`.
- New configuration is an `ORKTIN_*` environment variable (or the matching
  `-Dorktin.*` system property) and gets a row in the README's configuration
  table.

## Style

- Match the surrounding code: concise Kotlin, `suspend` functions for anything
  that does I/O, KDoc only where a declaration isn't self-explanatory.
- Test names are backtick sentences, e.g. ``fun `workspace is removed when the session stops`()``.
- Commit messages: imperative subject line (no prefix), blank line, then a
  short prose body explaining what changed and why.
