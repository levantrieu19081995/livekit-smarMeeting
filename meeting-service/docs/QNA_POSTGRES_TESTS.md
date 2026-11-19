# Isolated Q&A PostgreSQL verification

`MeetingQnaPostgresTest` and `MeetingQnaRuntimeTest` are opt-in PostgreSQL tests.
Ordinary Maven tests do not connect to PostgreSQL. The database test verifies a
V14-to-V15 upgrade, preserves every legacy table row, validates all entity
mappings and checks the database constraints/cascade/duplicate races. The runtime
test uses an actual random-port HTTP server, sessions, CSRF and Q&A services.
LiveKit, capture and STT are mocked in the Q&A HTTP tests; this does not verify
real media delivery or speech processing.

For this Windows workspace, run the generated helper from PowerShell:

```powershell
& .\target\qna-verification\Invoke-IsolatedQnaPostgres.ps1
```

The helper needs permission for Windows process startup and shutdown. The desktop
sandbox denies PostgreSQL's restricted-token startup and stop signals; run the
helper in the approved execution context. It validates the exact owned cluster
path and process identity before any forced shutdown fallback.

The helper snapshots `pom.xml` and `src` into a new evidence directory, initializes
a separate PostgreSQL 16 cluster there, selects a free loopback port and runs the
Q&A and existing poll PostgreSQL tests against that cluster. It never uses the
application database, restarts existing services or overwrites the main Maven
reports/classes. Its trust authentication is restricted to the temporary cluster
listening on `127.0.0.1`; no application credentials are copied or printed.

Each test owns a schema with a generated Q&A/poll prefix and drops only that
schema. The helper confirms zero remaining test schemas, stops only its exact
cluster in `finally`, restores process environment variables and writes
`result.json`, Maven reports and start/stop logs under its run directory. If a run
fails, preserve its evidence and inspect the failure before rerunning.

The P2 runtime assertions cover context/list/ask/upvote, active connection tokens,
session mismatch, CSRF, anonymous response filtering, ownership, duplicate retry,
pagination/sorting, reconnect and concurrent duplicate submissions/upvotes.
The P4 assertions preserve those checks and cover session moderator roles,
settings, status transitions, version conflicts and writes racing disable/end.
The P6 assertions additionally cover moderator-only report authorization,
history while disabled/ended/cancelled, anonymous data and the six-column
whitelist, a 205-question batch boundary, UTF-8/BOM and strict RFC 4180 decoding,
spreadsheet formula protection after Unicode whitespace/control characters,
unchanged source-table snapshots and no media/capture/STT calls.

Use `-RunLabel p6` to label the same focused set for phase evidence. Each run's
`result.json` includes actual test counts and owned process/schema/listener
cleanup flags. Preserve failed runs alongside successful evidence.
