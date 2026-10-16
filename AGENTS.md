# AGENTS.md

Guidance for coding agents working in this repository. Read the
[README](README.md) for what orkbox is and [docs/session-api.md](docs/session-api.md)
for the API it serves.

## What this project is

A sandbox service for coding agents: sessions with a workspace filesystem and
a way to run commands in it, behind an HTTP **Session API** (`orkbox-server`)
or embedded in-process as a library (`orkbox-core`). orkbox runs no agents and
talks to no LLM provider — don't add model clients, prompts or agent logic.

## Layout

| path | contents |
|---|---|
| `orkbox-core` | Library: `Orkbox` facade, `Config`, `model/` (API request/response types), `session/` (sessions, executions, files, event log), `runtime/` (`docker` and `local` runtimes). |
| `orkbox-server` | Ktor (CIO) HTTP server: `Routes.kt`, `Http.kt`, `Main.kt`, `OpenApi.kt`, the hand-written `src/main/resources/openapi.yaml`, and `ui.html`, the built-in UI page served at `/` and `/ui`. |
| `.github/workflows` | `ci.yaml` — runs `./mvnw verify` on PRs and pushes to main; uploads the shaded `orkbox.jar` on main. |
| `images/toolchain` | Dockerfile for `orknux/orkbox-toolchain`, the default session image. |
| `deploy/ansible` | The `orkbox` Ansible role and playbooks: install as a systemd service on Debian/Ubuntu, and remove. A new `ORKBOX_*` setting needs nothing there - `orkbox_settings` passes any of them through. |
| `docs/session-api.md` | The API specification. |

Package root: `io.mszymanski.orknux.orkbox`.

## Build and test

Kotlin on JDK 25, built with the Maven wrapper.

```sh
./mvnw verify                                   # build and run all tests
./mvnw -pl orkbox-core test                     # one module
./mvnw -pl orkbox-core test -Dtest=LocalSessionTest
./mvnw -Pnative -pl orkbox-server -am package  # native image (GraalVM as JAVA_HOME)
```

- Docker-backed tests (`DockerSessionTest`) skip themselves when no daemon is
  reachable; `ORKBOX_TEST_IMAGE` picks their image (default
  `debian:bookworm-slim`, deliberately without git/ripgrep so fallbacks get
  covered).
- Run `./mvnw verify` before considering a change done.

## CI

`ci.yaml` runs `./mvnw verify` on every pull request and on pushes to main.
The GitHub runners have a Docker daemon, so the docker-runtime contract
tests run there even when they skip locally. A green check is part of a
change being done; the PR's own head branch carries the workflow, so a new
one must land through a merged PR before it runs on that PR.

Pushes to main additionally upload the shaded `orkbox-server/target/orkbox.jar`
as a workflow artifact.

## Rules

- **Keep `orkbox-core` lean.** Its only dependencies are Kotlin stdlib,
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
  `orkbox-server/src/main/resources/META-INF/native-image/.../resource-config.json`.
- New configuration is an `ORKBOX_*` environment variable (or the matching
  `-Dorkbox.*` system property) and gets a row in the README's configuration
  table.

## Style

- Match the surrounding code: concise Kotlin, `suspend` functions for anything
  that does I/O, KDoc only where a declaration isn't self-explanatory.
- Test names are backtick sentences, e.g. ``fun `workspace is removed when the session stops`()``.
- Commit messages: imperative subject line (no prefix), blank line, then a
  short prose body explaining what changed and why.
