# Session API — proposal

Status: **accepted.** Implementation in progress.

## Conventions

- Base path `/v1`. JSON bodies, `camelCase` fields, timestamps in RFC 3339 UTC.
- IDs are opaque strings with a type prefix: `ses_…`, `exe_…`.
- Authentication: `Authorization: Bearer <token>`, a static shared secret
  configured on both orknux-server and orknux-codebox. orknux-codebox has no
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
  "image": "orknux/codebox-toolchain:1",   // docker only; must be on the configured allowlist
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

All fields are optional. Without `source` the workspace starts empty. A clone
that fails puts the session into `failed` with the git error as `failure`. Returns `201 Created` with the session (below) and
`Location: /v1/sessions/{id}`. The call returns once the session is `ready`; a
session that fails to start is returned with status `failed` and a `failure`
message (and still has to be deleted, so its logs stay readable).

### `GET /v1/sessions/{id}` — session details

```json
{
  "id": "ses_9f2c1a",
  "status": "ready",                        // creating | ready | stopping | stopped | failed
  "runtime": "docker",
  "image": "orknux/codebox-toolchain:1",
  "workdir": "/workspace",
  "env": { "CI": "true" },
  "resources": { "cpus": 2.0, "memoryMb": 4096, "diskMb": 10240, "pids": 1024 },
  "network": "full",
  "caches": ["maven", "gradle", "npm"],
  "labels": { "workspace": "acme", "run": "r-123" },
  "createdAt": "2026-10-10T10:00:00Z",
  "lastActivityAt": "2026-10-10T10:04:12Z",
  "expiresAt": "2026-10-10T12:00:00Z",
  "stoppedAt": null,
  "stopReason": null,                      // deleted | ttl_expired | idle_timeout | failed | server_shutdown
  "failure": null,
  "executions": { "total": 14, "running": 1 },
  "usage": { "cpuSeconds": 81.2, "memoryPeakMb": 1830, "diskUsedMb": 612 }
}
```

### `GET /v1/sessions` — list sessions

Query: `status`, `runtime`, `label=key:value` (repeatable, AND-ed), `limit`
(default 50), `cursor`. Returns `{ "items": [Session…], "nextCursor": "…" }`.

### `PATCH /v1/sessions/{id}` — update a session

```json
{ "ttlSeconds": 3600, "idleTimeoutSeconds": 600, "labels": { "agent": "reviewer" } }
```

Extends or shortens lifetime (measured from now) and merges labels (a `null`
value removes a label). Image, runtime and resources are fixed at creation.

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

Query: `since` (sequence number, exclusive), `types` (comma list), `limit`,
`follow` (SSE).

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

---

## Executions

An execution is one command run inside the session. It can be awaited (the
common agent case) or run in the background (dev servers, watchers, long builds).

### `POST /v1/sessions/{id}/executions` — execute a command

```json
{
  "command": "mvn -q test",          // run via `/bin/sh -lc`  — OR —
  "argv": ["mvn", "-q", "test"],     // run directly, no shell (exactly one of the two)
  "cwd": "service",                  // relative to workspace; default workspace root
  "env": { "MAVEN_OPTS": "-Xmx1g" }, // merged over session env
  "stdin": "…",                      // optional, closed after writing
  "timeoutSeconds": 600,             // killed with SIGTERM, then SIGKILL after a grace period
  "wait": true,                      // true: respond when finished (or when waitSeconds elapses)
  "waitSeconds": 120,                // cap on how long the HTTP call blocks; execution keeps running
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
`killed` means a signal was sent through the API or the session stopped.

Executions in one session may run concurrently.

### `GET /v1/sessions/{id}/executions` — list executions

Query: `status`, `limit`, `cursor`. Items omit `stdout`/`stderr`.

### `GET /v1/sessions/{id}/executions/{execId}` — execution details

Same shape as the create response. `?wait=true&waitSeconds=60` long-polls until
the execution finishes.

### `GET /v1/sessions/{id}/executions/{execId}/output` — full output

Query: `stream` (`stdout` | `stderr` | `combined`, default `combined`),
`offset` (byte offset), `limit` (bytes), `follow` (SSE until the execution ends).
Without `follow` returns `text/plain` with headers `X-Next-Offset` and
`X-Execution-Status`. Output is kept up to a configured cap per execution
(default 16 MiB); beyond that only the tail is retained.

### `POST /v1/sessions/{id}/executions/{execId}/stdin`

Raw body appended to the process's stdin. `?close=true` closes stdin afterwards.
Only for executions started without `stdin` in the request.

### `POST /v1/sessions/{id}/executions/{execId}/signal`

```json
{ "signal": "TERM" }   // INT | TERM | KILL | HUP
```

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

Edits apply in order, atomically (all or none). An `oldText` that matches zero
times — or more than once without `replaceAll` — fails with `edit_no_match` /
`edit_ambiguous` and the index of the edit. Returns `{ "path", "size", "etag",
"replacements": [1, 7] }`.

### `GET /v1/sessions/{id}/files?path=…` — list a directory

Query: `depth` (default 1), `glob` (e.g. `**/*.kt`), `includeHidden`
(default false), `limit`. Honours `.gitignore` unless `ignoreGitignore=true`.

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
Uses ripgrep inside the session when available, a built-in matcher otherwise.

### Archives — bulk transfer

- `PUT /v1/sessions/{id}/archive?path=…` — body is a `.tar` or `.tar.gz`
  (by `Content-Type`), extracted into `path`. For seeding a session with a
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

---

## Deliberately left out (for now)

- **Port forwarding / previews** of servers running in a session.
- **Snapshots / forking** a session's filesystem.
- **Pausing** sessions (`docker pause`) to save resources between agent turns.
- **An MCP endpoint** exposing the same operations as tools. orknux-server
  wraps the HTTP API into tools itself; an MCP facade could be added later for
  other clients.
- **Multi-node scheduling.** One orknux-codebox instance drives one Docker host.
