# Session API

The HTTP API served by `orkbox-server`. The same operations are available
in-process through `orkbox-core` (see the README), with the same error codes.

## Conventions

- Base path `/v1`. JSON bodies, `camelCase` fields, timestamps in RFC 3339 UTC.
- IDs are opaque strings with a type prefix: `ses_…`, `exe_…`.
- Authentication: `Authorization: Bearer <token>`, a static shared secret
  configured on both orknux-server and orkbox. orkbox has no
  users; tenancy and ownership are expressed by orknux-server through
  **labels** (e.g. `workspace`, `run`, `agent`).
- Errors are `application/problem+json` (RFC 9457) with a stable `code`:

  ```json
  { "type": "about:blank", "title": "Session not found", "status": 404,
    "code": "session_not_found", "detail": "No session ses_9f2c…" }
  ```

- **Paths** in file and execution endpoints are relative to the session's
  workspace root (inside a container: `/workspace`). Absolute paths are allowed
  only if they resolve inside the workspace; `..` escapes are rejected with
  `path_outside_workspace`. (In a container the agent can still reach the rest of
  the container through `execute`; the restriction exists so the `local` runtime
  cannot be used to touch the host through the file API.)
- Streaming endpoints use **Server-Sent Events** (`text/event-stream`) when
  called with `?follow=true`, otherwise return a finished JSON page.

---

## Sessions

### `POST /v1/sessions` — create a session

```json
{
  "runtime": "docker",                     // "docker" | "local"; default from config
  "image": "orknux/orkbox-toolchain:1",   // docker only; must be on the configured allowlist
  "env": { "CI": "true" },                 // default env for every execution
  "resources": {                           // docker only; capped by server config
    "cpus": 2.0,
    "memoryMb": 4096,
    "diskMb": 10240,
    "pids": 1024
  },
  "network": "full",                       // "full" | "none"
  "caches": ["maven", "gradle", "npm"],    // shared dependency cache volumes to mount
  "ttlSeconds": 7200,                      // hard lifetime
  "idleTimeoutSeconds": 900,               // reaped after this long without API activity
  "labels": { "workspace": "acme", "run": "r-123" },
  "ports": [3000],                         // make these reachable from outside the session
  "source": {                              // optional: clone a repository into the workspace
    "git": {
      "url": "https://github.com/acme/service.git",
      "ref": "main",                       // branch, tag or commit; default: remote HEAD
      "depth": 1,                          // shallow clone; omit for full history
      "env": { "GIT_ASKPASS": "…" }        // only for the clone, not stored on the session
    }
  }
}
```

All fields are optional. Without `source` the workspace starts empty; with it
the clone runs as ordinary executions (visible under `/executions` and in the
log), with `GIT_TERMINAL_PROMPT=0` so a missing credential fails instead of
hanging.

Returns `201 Created` with the session (below) and `Location:
/v1/sessions/{id}`, once the session is `ready`. A session that fails to start
(container error, failed clone) comes back with status `failed`, the reason in
`failure`, and its sandbox already removed; its record and logs stay readable
for the retention period.

Runtime differences: `image`, `resources`, `caches` and `network: "none"` are
`docker` only. `local` rejects `image` and `network: "none"`, and ignores
`resources` and `caches` (reported as `null` / `[]`). Requests above the
configured maximums fail with `limit_exceeded`; more active sessions than
allowed with `429 too_many_sessions`.

`ports` are asked for at creation, because a container's ports are published
when it starts. `docker` publishes each at a random host port on
`ORKBOX_PUBLISH_HOST` (loopback by default, so nothing is reachable from the
network unless the operator says so); `local` publishes nothing, since its
commands already run on the host, and reports each port as itself. The
session's `ports` say where each can be reached. A port outside 1..65535 fails
with `invalid_port`, more than `ORKBOX_MAX_PORTS` with `too_many_ports`.

### `GET /v1/sessions/{id}` — session details

```json
{
  "id": "ses_9f2c1a",
  "status": "ready",                        // creating | ready | stopping | stopped | failed
  "runtime": "docker",
  "image": "orknux/orkbox-toolchain:1",
  "workdir": "/workspace",
  "env": { "CI": "true" },
  "resources": { "cpus": 2.0, "memoryMb": 4096, "diskMb": 10240, "pids": 1024 },
  "network": "full",
  "caches": ["maven", "gradle", "npm"],
  "labels": { "workspace": "acme", "run": "r-123" },
  "createdAt": "2026-10-10T10:00:00Z",
  "lastActivityAt": "2026-10-10T10:04:12Z",
  "expiresAt": "2026-10-10T12:00:00Z",
  "idleTimeoutSeconds": 900,
  "stoppedAt": null,
  "stopReason": null,                      // deleted | ttl_expired | idle_timeout | failed | server_shutdown
  "failure": null,
  "executions": { "total": 14, "running": 1 },
  "usage": { "cpuSeconds": 81.2, "memoryPeakMb": 1830, "memoryCurrentMb": 640 },
  "ports": [ { "container": 3000, "host": "127.0.0.1", "hostPort": 49153 } ]
}
```

`usage` is read from the container's cgroup on this endpoint only (not in
lists), and is `null` for `local` sessions.

A session is **idle** when no API call has touched it for `idleTimeoutSeconds`
*and* none of its executions is running; idle and expired sessions are stopped
by a reaper that runs every few seconds.

### `GET /v1/sessions` — list sessions

Query: `status`, `runtime`, `label=key:value` (repeatable, AND-ed), `limit`
(default 50), `cursor`. Returns `{ "items": [Session…], "nextCursor": "…" }`.

### `PATCH /v1/sessions/{id}` — update a session

```json
{ "ttlSeconds": 3600, "idleTimeoutSeconds": 600, "labels": { "agent": "reviewer" } }
```

Extends or shortens lifetime (measured from now, but never past the configured
maximum lifetime counted from creation) and merges labels (a `null` value
removes a label). Image, runtime and resources are fixed at creation.

### `DELETE /v1/sessions/{id}` — stop and destroy

Kills running executions, removes the container / directory. Returns `200` with
the final session. The session record and its logs are kept for a configurable
retention period (default 24 h) so they can still be read; after that `GET`
returns `404`.

---

## Logs

### `GET /v1/sessions/{id}/logs` — session log

The session's chronological event log: lifecycle events, every execution's
start/finish, and (optionally) execution output. This is the audit trail of
"what happened in this sandbox".

Query: `since` (sequence number, exclusive), `types` (comma list), `limit`
(default 1000), `follow` (SSE: one `event: <type>` per log event with
`id: <seq>`, a `: ping` comment every 15 s, and a final `event: end` once the
session has stopped).

```json
{
  "items": [
    { "seq": 1, "at": "…", "type": "session.created",  "data": { "image": "…" } },
    { "seq": 2, "at": "…", "type": "session.ready",    "data": { "startupMs": 840 } },
    { "seq": 3, "at": "…", "type": "execution.started","data": { "executionId": "exe_1", "command": "mvn -q test" } },
    { "seq": 4, "at": "…", "type": "execution.output", "data": { "executionId": "exe_1", "stream": "stdout", "text": "…" } },
    { "seq": 5, "at": "…", "type": "execution.finished","data": { "executionId": "exe_1", "exitCode": 0, "durationMs": 31000 } },
    { "seq": 6, "at": "…", "type": "file.written",     "data": { "path": "src/Main.kt", "bytes": 512 } },
    { "seq": 7, "at": "…", "type": "session.stopped",  "data": { "reason": "deleted" } }
  ],
  "nextSeq": 7
}
```

Event types: `session.created`, `session.ready`, `session.failed`,
`session.updated`, `session.stopping`, `session.stopped`,
`execution.started`, `execution.output`, `execution.finished`,
`file.written`, `file.edited`, `file.deleted`, `file.moved`, `archive.uploaded`.
`execution.output` is excluded unless `types` asks for it, since it dominates the log.
The log keeps the most recent events up to a configured count (default 20 000).

---

## Executions

An execution is one command run inside the session. It can be awaited (the
common agent case) or run in the background (dev servers, watchers, long builds).

### `POST /v1/sessions/{id}/executions` — execute a command

```json
{
  "command": "mvn -q test",          // run through the runtime's shell — OR —
  "argv": ["mvn", "-q", "test"],     // run directly, no shell (exactly one of the two)
  "cwd": "service",                  // relative to workspace; default workspace root
  "env": { "MAVEN_OPTS": "-Xmx1g" }, // merged over session env
  "stdin": "…",                      // optional, written then closed
  "interactive": false,              // true: keep stdin open for /stdin (excludes "stdin")
  "timeoutSeconds": 600,             // default 1800; SIGTERM, then SIGKILL after a grace period
  "wait": true,                      // true: respond when finished (or when waitSeconds elapses)
  "waitSeconds": 120,                // default 300, max 3600; the execution keeps running after
  "maxOutputBytes": 65536            // per stream, in the response; full output stays retrievable
}
```

Response (`200` if finished, `202` if still running):

```json
{
  "id": "exe_7c1d",
  "sessionId": "ses_9f2c1a",
  "status": "succeeded",           // running | succeeded | failed | timed_out | killed
  "command": "mvn -q test",
  "argv": null,
  "cwd": "service",
  "exitCode": 0,
  "signal": null,
  "startedAt": "…",
  "finishedAt": "…",
  "durationMs": 31012,
  "stdout": "…",                   // head+tail if over maxOutputBytes
  "stderr": "",
  "stdoutBytes": 18234,
  "stderrBytes": 0,
  "truncated": false
}
```

`failed` means a non-zero exit code; `timed_out` means `timeoutSeconds` hit;
`killed` means a signal was sent through the API or the session stopped. A
program that does not exist fails like it would in a shell (exit code 127).

Unless `interactive` is set, stdin is closed right away, so a command that
reads it sees end of input instead of hanging.

The shell a `command` runs in depends on the runtime and is reported by
`/v1/info`: in containers `bash -c` (falling back to `sh -c`); on a local
Linux/macOS host `/bin/sh -lc`; on a local Windows host `cmd.exe /d /s /c`.
The local shell can be changed through configuration. In containers every
command runs in its own process group, so signals and timeouts reach its
children too; locally the process tree is signalled.

Executions in one session may run concurrently.

### `GET /v1/sessions/{id}/executions` — list executions

Query: `status`, `limit`, `cursor`. Items omit `stdout`/`stderr`.

### `GET /v1/sessions/{id}/executions/{execId}` — execution details

Same shape as the create response. `?wait=true&waitSeconds=60` long-polls until
the execution finishes; `maxOutputBytes` (default 65536) sizes the excerpts.

### `GET /v1/sessions/{id}/executions/{execId}/output` — full output

Query: `stream` (`stdout` | `stderr` | `combined`, default `combined`),
`offset` (byte offset), `limit` (bytes), `follow` (SSE until the execution ends).
Without `follow` returns `text/plain` with headers `X-Next-Offset` and
`X-Execution-Status`. With `follow`, each chunk is an `event: output` with
`data: {"offset": …, "text": "…"}` and `id` set to the next offset, and the
stream ends with `event: end` carrying the finished execution. Output is kept
up to a configured cap per stream (default 4 MiB); beyond that only the tail is
retained, and an `offset` that fell out of it reads from the oldest byte left.

### `POST /v1/sessions/{id}/executions/{execId}/stdin`

Raw body appended to the process's stdin. `?close=true` closes stdin afterwards.
Only for executions started with `interactive: true`; otherwise `409 stdin_closed`.
Returns `204`.

### `POST /v1/sessions/{id}/executions/{execId}/signal`

```json
{ "signal": "TERM" }   // INT | TERM | KILL | HUP
```

Returns the execution. On a local Windows host there are no signals: `KILL`
terminates the process tree forcibly and the others ask it to terminate.

---

## Files

Structured file access, so agents don't have to quote file contents through a
shell. All operations are logged as `file.*` events.

### `GET /v1/sessions/{id}/files/content?path=…` — read a file

Returns raw bytes (`application/octet-stream`, or `text/plain; charset=utf-8`
when the file is valid UTF-8). Text helpers: `startLine`, `endLine` (1-based,
inclusive) return only that range. Headers: `X-File-Size`, `X-Line-Count` (text),
`ETag` (content hash).

### `PUT /v1/sessions/{id}/files/content?path=…` — write a file

Raw body is the new content. Creates parent directories. Query: `mode` (octal,
e.g. `755`), and optional `If-Match: <etag>` header to refuse writing over a file
that changed since it was read (`412`). Returns `{ "path", "size", "etag" }`.

### `POST /v1/sessions/{id}/files/edit` — targeted edits

Search-and-replace edits, the format agents already use:

```json
{
  "path": "src/main/kotlin/App.kt",
  "edits": [
    { "oldText": "val port = 8080", "newText": "val port = config.port" },
    { "oldText": "println(", "newText": "logger.info(", "replaceAll": true }
  ]
}
```

Or by line numbers, from a read or a search - `oldText` is then an optional
guard that the lines still hold it:

```json
{ "path": "src/App.kt", "edits": [ { "startLine": 12, "endLine": 14, "newText": "val port = config.port" } ] }
```

Or by regular expression, with `"regex": true` - `oldText` is the pattern
(multiline, so `^` and `$` match at line breaks) and `newText` may refer to its
groups as `$1`; an invalid pattern fails with `invalid_regex`.

Edits apply in order, atomically (all or none); line numbers refer to the file
as the edits before them left it, so give several line edits bottom-up. An
`oldText` that matches zero times — or more than once without `replaceAll` —
fails with `edit_no_match` / `edit_ambiguous` (`422`, with `editIndex` in the
problem); lines outside the file fail with `edit_bad_lines`. A file with CRLF
lines is matched by text with LF ones and keeps its endings. Returns `{ "path", "size", "etag",
"replacements": [1, 7] }`.

### `GET /v1/sessions/{id}/files?path=…` — list a directory

Query: `depth` (default 1, or unlimited when a `glob` is given), `glob` (e.g.
`**/*.kt`; a glob without `/` matches file names at any depth), `includeHidden`
(default false), `limit` (default 1000). Honours `.gitignore` (via `git
check-ignore`, when git and a repository are present) unless
`ignoreGitignore=true`.

```json
{
  "path": "src",
  "entries": [
    { "path": "src/main", "type": "directory" },
    { "path": "src/main/App.kt", "type": "file", "size": 812, "mode": "644", "modifiedAt": "…" },
    { "path": "src/link", "type": "symlink", "target": "../other" }
  ],
  "truncated": false
}
```

### `GET /v1/sessions/{id}/files/stat?path=…`

One entry as above, or `404 file_not_found`.

### `DELETE /v1/sessions/{id}/files?path=…`

Query `recursive=true` required for non-empty directories.

### `POST /v1/sessions/{id}/files/move`

```json
{ "from": "a.txt", "to": "b/a.txt", "overwrite": false }
```

### `POST /v1/sessions/{id}/files/search` — grep

```json
{
  "pattern": "TODO\\(",
  "regex": true,
  "path": "src",
  "glob": "**/*.kt",
  "caseSensitive": false,
  "contextLines": 2,
  "maxResults": 200
}
```

Returns `{ "matches": [{ "path", "line", "column", "text", "before": [], "after": [] }], "truncated" }`.
Uses ripgrep inside the session when available (which also honours
`.gitignore` and skips hidden files); otherwise `grep` in containers (no context
lines) or a built-in matcher for local sessions. `maxResults` is capped at
10 000 and `contextLines` at 20.

### Archives — bulk transfer

- `PUT /v1/sessions/{id}/archive?path=…` — body is a `.tar` or `.tar.gz`
  (detected from its first bytes), extracted into `path`. Returns `204`. For seeding a session with a
  repository checkout orknux-server already has.
- `GET /v1/sessions/{id}/archive?path=…` — returns `path` as `.tar.gz`. For
  pulling results (build artifacts, a patched tree) back out.

Besides `source.git` at creation, a repository can be cloned at any time with
`execute("git clone …")`; credentials, if any, go in that execution's `env`.

## Execution identity

Inside a `docker` session commands run as **root**, so agents can install
system packages (`apt-get install …`). Isolation comes from the container (and
optionally gVisor/Kata), not from the user inside it. In a `local` session
commands run as the service's own OS user.

---

## Service

| endpoint | purpose |
|---|---|
| `GET /v1/info` | version, enabled runtimes, allowed images, default and maximum limits, available caches |
| `GET /healthz` | liveness |
| `GET /readyz` | readiness (runtime backends reachable, e.g. Docker daemon) |
| `GET /openapi.yaml` | this API as an OpenAPI 3.1 document (no token needed) |
| `GET /docs` | Swagger UI for it (no token needed) |

---

## Deliberately left out (for now)

- **Port forwarding / previews** of servers running in a session.
- **Snapshots / forking** a session's filesystem.
- **Pausing** sessions (`docker pause`) to save resources between agent turns.
- **An MCP endpoint** exposing the same operations as tools. orknux-server
  wraps the HTTP API into tools itself; an MCP facade could be added later for
  other clients.
- **Multi-node scheduling.** One orkbox instance drives one Docker host.
