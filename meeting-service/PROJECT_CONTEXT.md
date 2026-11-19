# PROJECT_CONTEXT.md — meeting-service

> Project: `meeting-service`  
> Stack: Spring Boot 3.4.x / Java 17 / PostgreSQL  
> Purpose: Backend core context, business workflow, persistence, Java↔Python orchestration decisions, and Minutes pipeline status.
>
> ## Codex maintenance rule
> After every task that modifies this repository, Codex MUST update this file before finishing.
>
> Update only affected sections:
> - `Current implementation`
> - `API contracts`
> - `Data/persistence`
> - `Minutes processing pipeline`
> - `Known constraints`
> - `Next work`
> - `Change log`
>
> Do not rewrite unrelated sections. Do not invent Python behavior; use the agreed contract and actual source.

---

# 1. Responsibility of this repository

`meeting-service` is the core business backend.

Responsibilities:

- Authentication / authorization.
- Meeting lifecycle.
- Participant management.
- Documents.
- Chat persistence.
- Capture session metadata.
- Minutes/version persistence.
- Minutes processing job state.
- Orchestration between FE and FastAPI.
- Transcript persistence.
- Voice + Chat timeline derivation.
- Future LLM Minutes generation.

Java is the business source of truth.

Target:

```text
Frontend
   ↓
Java
   ↓
FastAPI STT
   ↓
Java
   ↓
Transcript + Chat
   ↓
LLM
   ↓
Minutes V1
```

---

# 2. Current implementation

## Meeting points and speaking history (2026-10-08)

- Add creation mode POINT: PUBLIC with the normal scheduled form and 1–100
  distinct point names (1–150 characters each). Optional roster retains the
  create-time one-chair/one-secretary validation. Ordinary meetings are unchanged.
- Join-info/detail expose stable point IDs/names. POINT join requires a scoped
  pointId before LiveKit operations; selected point belongs to a connection,
  so different devices of one actor do not overwrite each other's point.
  Point list becomes immutable after the first join connection; detail exposes
  pointsEditable. Metadata edit ownership remains the existing PUBLIC policy.
- POINT PUT accepts an optional complete role assignment roster with existing
  participantKey/new account invitations. Metadata/points/roles are atomic;
  final one-chair/one-secretary rules permit role swaps without partial saves.
  Omitted officers become MEMBER without deleting public attendees or history.
  Standalone POINT roster writes check the same unique officer rule under lock.
- POINT creation requires a clientRequestId UUID. A creator row lock before
  LiveKit and durable meeting_point_creation_receipts ledger deduplicate successful
  retries by actual creator/key/canonical body hash and retain the original
  token-free MeetingResponse. Changed payload conflicts; deleted meeting receipt
  remains with nullable meeting_id and returns 410 rather than recreating.
  Deterministic roomName per creator/key also prevents a new room name after
  uncertain LiveKit create response. Ordinary create APIs retain their behavior.
  Malformed meeting JSON/path input (including new clientRequestId/pointId)
  receives sanitized 400, while valid workflows and business errors are unchanged.
- New /api/meetings/{id}/speaking context, bounded paginated history, idempotent
  request, versioned approve/finish/cancel. All participants see the history;
  only creator/non-removed CHAIRMAN/SECRETARY approve (bound account must be
  ACTIVE; canonical guest officers require valid participant connection proof).
  Requester or moderator can
  cancel WAITING / finish SPEAKING. No microphone permission changes or changes
  to chat/Q&A/poll/AI/transcript attribution.
- V19 adds meeting_points, connection.point_id and meeting_speaking_requests,
  composite scoped FKs, unique retry/active-request indexes and state/timestamp
  constraints. Snapshots preserve names; event times use UTC and JSON Instant Z.
  Meeting locks serialize request/actions with join, end, remove and point edits.
  Terminal lifecycle closes active logs; 30s cleanup recovers scheduled expiry.
- POINT guests require the previous private chatWriteToken as guestResumeToken
  to reuse guestId. No proof creates a new actor; wrong proof is rejected.
  Current credentials/account/meeting scope are verified server-side, including
  disabled accounts and session/token mismatch; terminal history is read-only.
- API and rollout details: docs/MEETING_POINTS.md. No application database or
  deployment has been changed. Isolated PostgreSQL 16.8 full regression snapshot,
  including POINT creation idempotency, passed 865/865 (zero failures/errors/skips):
  target/qna-verification/point-final-release-postgres-20261008-145943-5354474c/result.json.
  After final scoped malformed-input and canonical guest-officer checks, 84/84
  passed on final source (82 feature tests: 59 HTTP/runtime + 23 PostgreSQL, plus
  2 MeetingController tests; zero skips), evidence:
  target/qna-verification/point-final-complete-postgres-20261008-151825-67a5c07f/result.json.
  These are distinct source snapshots, not a combined single test run. Owned
  schemas/processes/listeners were fully removed/stopped. Current backend package
  built successfully (target/point-final-package.log). Media/load capacity
  and actual hardware were not exercised by the mocked LiveKit/capture tests.

Change log — 2026-10-08: Implement POINT creation/join selection and durable
speaking requests/timeline with narrow moderator roles and lifecycle consistency.

## AI Result / one-use AI conclusion (2026-10-07)

- New GET `/api/meetings/{id}/conclusion` and CSRF/session-protected POST
  `/api/meetings/{id}/conclusion/generate` use existing minutes view/edit policies.
  PUBLIC: creator edits; PRIVATE: creator/chairman/secretary edit. No admin bypass
  or changes to Q&A, polls, capture, STT, meeting visibility or media permissions.
- Generate requires a completed meeting and latest DRAFT/FINAL minutes. Request
  carries expectedMinutesVersion, expectedStoredConclusion and optional manualContent.
  A stale source conflicts. Existing active jobs are returned; GENERATED always
  conflicts, including after later human edits. FAILED can start a new attempt.
- Flyway V18 adds one meeting_conclusions row per meeting, generation UUID,
  immutable successful input/context snapshots, original structured AI output,
  finalContent, durable QUEUED/PROCESSING/GENERATED/FAILED state and worker lease.
  Meeting row locks serialize generation, completion and normal minutes edits.
- The configured default taskScheduler (pool size 5 by default) is preserved
  alongside a dedicated conclusionScheduler; a blocking-AI test proves capture/STT
  work can still execute. A dedicated scheduler performs Java -> FastAPI `/api/ai/conclusions/generate`
  with X-Internal-Api-Key. Default read timeout 120s, lease 300s, bounded delivery
  retries reuse the generation UUID/context. FastAPI receipts prevent repeated
  successful provider calls on network retry. Restart/refresh retains Java state.
- Sources include meeting metadata, latest minutes, CLOSED poll option/ballot
  counts, visible OPEN/ANSWERED Q&A with written answers, persisted chat opinions,
  and manual conclusion. Anonymous Q&A names are masked; pending/hidden/deleted
  questions and unclosed polls are omitted. Oversized inputs fail without truncation.
- Valid output creates a new normal minutes DRAFT version with AI_GENERATED source.
  Existing versions remain unchanged. Manual text is retained before the supplement;
  concurrent human edits take priority. Original manual snapshot and AI result stay
  separate from finalContent, which follows subsequent existing minutes edits.
  Render/export uses the canonical conclusion; FINAL-only PDF rules remain intact.
- Validation: 786 Java tests passed, zero failures/errors/skips on isolated
  PostgreSQL 16.8, including real HTTP/CSRF, concurrent start, one-success lock,
  retries, private/public permission checks, stale source, lease recovery, historical
  preservation, and real rendered PDF containing manual plus AI conclusion.
  Evidence: target/qna-verification/ai-result-final-postgres-20261007-201929-fa1fdc80/result.json.
  FastAPI 221 and frontend 228 tests passed; real OpenAI synthetic-data smoke passed.
- Deployment/configuration: docs/AI_RESULT.md. Application database and deployed
  services have not been modified by verification; V18 runs on backend startup.

Change log — 2026-10-07: Implement the agreed AI Result conclusion workflow, durable
one-success lock, source snapshots, protected human content and existing minutes export.

## Written Q&A answers (2026-10-07)

This supersedes earlier status-only ANSWER implementation notes.

- Flyway V17 adds nullable `answer` (VARCHAR 5000) and
  `answer_author_display_name` (VARCHAR 255) to meeting_qa_questions. A CHECK
  requires a nonblank answer, responder snapshot and answered_at together.
  Existing status-only answers remain valid; no legacy question row is replaced.
- The versioned actions endpoint accepts optional `answer` for ANSWER only.
  Nonblank trimmed content is bounded to 5000 characters. OPEN questions can be
  answered and ANSWERED questions filled/edited. Responder name comes from the
  verified participant or creator principal; it is never caller-supplied.
- Responses and list/history add nullable answer/answerAuthorDisplayName.
  answeredAt records the latest changed written answer. Fresh-version identical
  content is a no-op; stale versions conflict. Hide/delete retain answer fields.
- Null-answer legacy requests keep the existing status-only behavior for client
  compatibility. New frontend requires typed text and verifies the receipt;
  deployment must include both frontend and backend/V17.
- CSV appends “Nội dung trả lời” and “Người trả lời” after the original six
  columns, using the same snapshot/batched reads and formula/text escaping.
- Existing canManage access, meeting locks, version checks, anonymous question
  privacy, terminal read-only, CSRF and credential handling are unchanged.
- Validation: 767 tests, zero failures/errors/skips on isolated PostgreSQL 16.8;
  owned schemas/processes/listener were fully stopped/removed. Added coverage for
  every participant role, reload/author snapshot, legacy fill/edit/no-op/stale
  version, limits, hidden/pending/deleted state, terminal history, CSV escaping,
  actual HTTP/session/CSRF roundtrip and migration/database constraints.
- Evidence: target/qna-verification/written-answer-final-postgres-20261007-171637-c94a0b26/result.json.
  Final package built successfully; 341 source/resource/test files match the
  validated snapshot. Frontend production build and 222 tests also passed.
  No production deployment or service restart was performed.

Change log — 2026-10-07: Persist written Q&A answers, display metadata and include
answer content/responder in history and CSV.

## Meeting modes and consolidated permission contract (2026-10-07)

This section supersedes older moderator/officer/system-role restrictions in the
P1–P7 implementation notes below.

- `MeetingCreationMode`: INSTANT, LATER, SCHEDULED. Omitted mode preserves legacy
  SCHEDULED API validation. Every enabled, persisted account can create meetings;
  ownership comes from the authenticated principal, never client input.
- INSTANT is PUBLIC, starts at server creation time and has NULL end. LATER is
  PUBLIC with NULL start/end until the first successful join under the meeting
  row lock; no end is added until explicit meeting end. SCHEDULED preserves
  mandatory title/dates/private roster validation, one chairman/secretary maximum.
- V16 adds `creation_mode` (legacy default SCHEDULED), nullable start/end, guarded
  schedules, optional participant-backed poll creator and composite creator FK.
  The schedule check is NOT VALID for legacy compatibility, while new writes
  are checked. Existing meeting/chat/capture/minutes/poll/Q&A data is retained.
- Shared `MeetingVisibility`: PUBLIC for enabled signed-in viewers; PRIVATE for
  creator or non-removed roster/email invitation. SQL lists/counts/pagination and
  direct reads agree. Private creators are still invited as MEMBER when absent.
- `MeetingPermissions` is authoritative for edit/delete/participants/end/capture,
  documents and minutes: PUBLIC creator only; PRIVATE creator/chairman/secretary.
  System roles confer no ownership bypass, including admin override/reset tools.
  Meeting/detail/minutes-list responses return `canManage`. Owner-edited minutes
  use `OWNER_EDITED`. Minutes list retains the existing completed-meeting scope.
- Q&A and polls share `MeetingParticipantAccess`: active participants of every
  role, including guests, can manage. Valid PENDING or CONNECTED join credentials
  work before webhook confirmation. Cross-meeting, mismatched, removed, revoked
  and disabled credentials never fall back to a privileged session. Outside
  signed-in PUBLIC viewers can read; only participants/creator manage.
- Polls accept `X-Meeting-Participant-Token` across their controller endpoints.
  Guest creators are persisted through `created_by_participant_id`. Every active
  participant is included at open; late voters get an auditable snapshot under
  the meeting lock. Existing unique ballot/option/snapshot constraints, retry
  receipts, choice limits, allow-change setting and result timing remain.
- Terminal meetings reject new QA/poll writes; history/report and unchanged
  committed retries remain readable. Anonymous author details remain redacted.
  HTTP CSRF/session protections and external media/capture/STT contracts remain.
- Verification: 755/755 backend tests in the final isolated PostgreSQL run, zero
  failures/errors/skips; legacy migrations/data, real HTTP owner/private/guest
  flows and existing lifecycle/concurrency checks pass. 220 frontend tests,
  TypeScript, production build and responsive browser checks are recorded in
  `target/meeting-flow-verification/REPORT.md`. The packaged jar is source-matched.
- No running application database migration/server restart was performed. Deploy
  the tested FE and BE together, applying V16 through the existing Flyway startup.

Change log — 2026-10-07: Added dateless INSTANT/LATER creation, owner/private-officer
management and open participant poll/QA actions; preserved terminal/identity rules.

## Open PUBLIC reads and roster-only PRIVATE visibility (2026-10-07)

- Every authenticated system role can GET the meeting list. Shared
  `MeetingVisibility` allows PUBLIC reads and requires an account-bound roster
  entry or matching unbound email invitation for PRIVATE. REMOVED entries are
  excluded. Creators can view their meetings; ADMIN/manager have no ownership bypass.
- Meeting lists apply visibility in SQL before counts/pagination. Direct reads
  share the rule for detail, participants, minutes/versions/transcript, documents
  and files, processing/editor draft, PDF rendering, capture metadata, polls and
  Q&A history. Existing join-info bootstrap and join validation remain.
- PRIVATE creation and PUBLIC-to-PRIVATE conversion invite the creator as MEMBER
  only when absent, so a successful change remains readable by its creator.
  Existing officer assignments/validation and duplicate-member checks remain.
- Minutes listing/summary no longer require an officer role. Explicit role
  filters match the current account and exclude removed entries, including MEMBER
  filters; `myRole` is null for viewers. Removed officers cannot edit minutes.
- Read and mutation authorization are separate. Mutations now follow the owner/
  private-officer contract above; poll/Q&A management is open to participants.
  CSRF, terminal state and credential scope protections remain. Administrator
  maintenance also checks the same meeting management permission.
- Final regression: 735/735 PASS in 51 suites, no failures/errors/skips, on an
  owned PostgreSQL 16.8 cluster. New real HTTP/session/CSRF cases cover PUBLIC reads
  and PRIVATE membership for USER/MANAGER/ADMIN, email invitations, revocation,
  pagination/summary and creator participation. External LiveKit/capture/STT are
  controlled. All 335 source files matched the tested snapshot used to package
  the runnable backend jar. The owned cluster/schema/processes were cleaned up.
  Evidence: `target/open-access-verification/REPORT.md`. The current application
  server/database were not restarted or migrated; the new backend must be loaded.

## Meeting creation participant roles (2026-10-06)

- Creating either PUBLIC or PRIVATE meetings now rejects more than one SECRETARY
  before allocating a LiveKit room or persisting the meeting. The existing
  maximum-one-CHAIRMAN validation remains unchanged. Secretary remains optional;
  existing empty PUBLIC creation and non-empty PRIVATE roster rules are preserved.
- MEMBER was already accepted by the create API and remains supported alongside
  one chairman and an optional secretary. No DTO, database or security changes
  are required for member selection in the frontend.
- This change applies only to initial creation. Existing meeting updates,
  participant additions/role changes, legacy multi-secretary rosters, joined
  GUEST editing, lifecycle, capture, polls and Q&A behavior are unchanged.
- Focused checks: 40 PASS (31 meeting service tests and 9 existing participant
  service tests), including eight new PUBLIC/PRIVATE create cases. Baseline
  hashes, source comparison and logs are in `target/participant-create-verification`.
- Real HTTP/PostgreSQL create acceptance: 15 PASS, covering directory-account
  bindings and exact roles, duplicate-secretary rejection before room allocation,
  optional secretary, chairman validation, session/CSRF and existing legacy
  multi-secretary/member/connected-GUEST edit behavior. External LiveKit,
  capture and STT integrations are mocked; session, API, services and PostgreSQL
  persistence are real.
- Full backend regression: **719/719 PASS across 51 suites**, zero failures/errors/skips, on an
  owned PostgreSQL 16.8 cluster. All 334 snapshot source files matched their
  hashes; no source changed during the run. Evidence:
  `target/participant-create-verification/create-full-postgres-20261006-105556-549904b2`
  (`result.json`, `focused-maven.log`, `source-proof.json`, `runtime-cases.json`).
  The owned cluster stopped normally, with zero owned schemas/child processes
  and its port closed. Current application databases/services were untouched.

## Q&A join-issued participation update (2026-10-07)

- User explicitly requested that every meeting participant can ask without waiting
  for CONNECTED confirmation. Read-only room 37 diagnosis matched lvtrieu's exact
  connection ACTIVE in LiveKit with PENDING/INVITED in the configured dev DB.
- `MeetingQaAuthorizationService` accepts valid same-meeting join tokens for
  PENDING or CONNECTED sessions. The Actor flag/guard now describe participant
  credentials instead of claiming an actual CONNECTED state. Q&A context enables
  asking/upvoting for all roles using those credentials; no connection is rewritten.
- LEFT/REMOVED, removed participants, disabled accounts, invalid/cross-meeting
  tokens and cookie/token identity mismatch remain denied. Moderator authority,
  CSRF, privacy, deduplication, Q&A settings and ended-meeting rules remain.
- Only the two Q&A production service files changed. Existing join, webhook, chat,
  poll, capture, STT, minutes, database schema and frontend production code did not.
- Focused Q&A service/security/real HTTP checks: 146 PASS. Full backend suite:
  726/726 PASS in 51 suites, zero failures/errors/skips, isolated PostgreSQL 16.8.
  New HTTP cases use the actual join endpoint to issue a PENDING token for each of
  CHAIRMAN, SECRETARY, MEMBER and GUEST, then ask/upvote without a webhook. They
  verify CSRF rejection, one persisted receipt, no moderator elevation and unchanged
  PENDING/joinedAt state. External LiveKit room/token generation is mocked.
- All 334 source files matched the tested snapshot. The isolated cluster was
  stopped with zero owned schemas/child processes and a closed port. A runnable
  backend jar was built successfully from that snapshot. Evidence/artifact:
  `target/qna-participation-verification/REPORT.md` and `meeting-service.jar`.
  The application server/database were not restarted or migrated by verification;
  the new backend must be loaded before deployed room behavior changes.

## Q&A / Hỏi đáp — P1–P7 backend complete (2026-10-06)

- Google Meet-style rules and phase gates are documented in
  [docs/QNA_IMPLEMENTATION.md](docs/QNA_IMPLEMENTATION.md).
- Additive Flyway V15 introduces `meeting_qa_settings`, `meeting_qa_questions`
  and `meeting_qa_upvotes`. Same-meeting composite foreign keys, unique
  participant-scoped send UUIDs and unique participant/question upvotes are enforced.
- New `/api/meetings/{id}/qa/context`, `/qa/questions` GET/POST and
  `/qa/questions/{questionId}/upvote` PUT use a Q&A-specific authorization policy.
  Session members may read history; asking/upvoting requires a join-issued token
  from a PENDING or CONNECTED connection, even for authenticated accounts. The
  2026-10-07 user-requested update removes dependence on a CONNECTED webhook;
  every participant role, including guests, can participate with its valid token.
  Session/token mismatch,
  removed participants and disabled database accounts are rejected. CSRF remains
  enabled; only Q&A routes admit guest credentials to the service policy.
- Anonymous names and identity metadata are omitted from all response scopes,
  including moderators. Privacy filters run in database queries before pagination
  and total counts. Existing chat, poll, join, lifecycle, recording and minutes
  sources are unchanged.
- Writes serialize on the existing meeting row. New questions/upvotes require an
  enabled IN_PROGRESS meeting. Identical authorized retries return their saved receipt
  after disable/end without writing; hidden/deleted receipts remain moderator-only.
- Send/upvote responses are refreshed from persisted rows, preserving PostgreSQL
  timestamp precision across initial responses, reloads and idempotent retries.
- P2 focused checks: 38 PASS (32 H2 persistence/business tests and 6 MockMvc
  security/validation tests). Real PostgreSQL/HTTP verification is maintained in
  `target/qna-verification`; no application database migration or restart occurred.
- P4 adds PUT `/qa/settings` and POST `/qa/questions/{questionId}/actions`.
  Session moderators supply expected versions; stale writes conflict. Settings can
  re-enable disabled Q&A without changing existing pending/anonymous questions.
  APPROVE, ANSWER, HIDE and DELETE follow the documented transition matrix;
  deletion remains a private tombstone. Current-version no-ops preserve timestamps
  and versions. Moderator changes remain possible while Q&A is disabled, but all
  writes stop after the meeting ends. Persisted response timestamps are refreshed.
- P4 focused checks: 72 PASS (64 H2 business/persistence and 8 MockMvc security
  cases), including the complete 20-case action/status matrix and optimistic
  version conflicts. Real PostgreSQL/HTTP and frontend phase gates are tracked in
  the implementation document.
- P6 adds moderator-only GET `/qa/report`, readable in live, completed, cancelled
  and Q&A-disabled meetings without requiring an active room connection. Supplied
  token validation and session moderator authorization remain mandatory.
- The report exports six business fields only: question text, safe author display
  name, status, upvote total, sent time and answered time. Pending, hidden and
  deleted records remain visible only to moderators; anonymous identity, account,
  participant, email and client UUID fields are never exported.
- CSV uses UTF-8 BOM, quoted RFC 4180 cells and CRLF, with spreadsheet formula
  protection after leading whitespace, control and Unicode format characters.
  Files use a numeric meeting filename and `Cache-Control: no-store`. Error
  responses retain JSON content type even for clients requesting CSV.
- Reports use a whitelisted seven-field projection in deterministic 200-row
  database batches inside a read-only repeatable snapshot, avoiding managed
  question/participant aggregates. No recording, chat, poll or minutes writes occur.
- P6 focused checks: 95 PASS (72 H2 service, 10 security/controller, 13 CSV
  encoder cases), including a 205-question batch boundary, all status/privacy
  scopes, export bytes and authorization, formula guards and unchanged versions.
- P7 full backend verification: **696/696 PASS across 50 suites**, with zero
  failures, errors or skipped tests. This includes 168 Q&A tests and all 528
  existing regression tests; both PostgreSQL opt-in suites and real HTTP tests
  were enabled against an isolated PostgreSQL 16.8 cluster. Earlier phase evidence
  remains 95 focused P6 checks and 113 PostgreSQL/HTTP Q&A/Poll checks.
- Two preexisting `MeetingServiceTest` expected message strings were aligned with
  the unchanged production validation contract. Exception types, method and
  no-save assertions were retained. Existing production sources remain byte-identical
  to the baseline except the Q&A-only security matcher/comment; no existing chat,
  poll, participant, meeting, capture, STT or minutes business logic changed.
- Final independent source review confirms meeting-row serialization, anonymous
  DTO/report privacy, SQL filtering before counts, scoped error handling and
  additive V15 integrity/cascade behavior. Reports read whitelisted projections
  inside a repeatable snapshot without mutating any source tables.
- Evidence: `target/qna-verification/p7-postgres-20261006-080117-d13b9a27`
  and `target/qna-verification/P7_BACKEND_READOUT.md`; the consolidated
  `target/qna-verification/REPORT.md` tracks frontend/build/browser gates.
  The owned test cluster stopped normally; no owned schemas, child processes or
  listening ports remained. Existing application databases and services were
  not migrated or restarted.

## Poll / Biểu quyết — P1–P7 implementation (2026-10-05)

P1 adds Poll entities/enums/repositories and Flyway V14. P2 adds Poll lifecycle
APIs, authorization, draft configuration and atomic voter snapshots. P3 adds
principal-derived my-vote, idempotent retry/change-vote and server-filtered results.
P4–P6 add frontend management/voting, restore, polling and persisted history.
P7 adds same-transaction closure on canonical end/cancellation and real PostgreSQL
HTTP/race tests. Connected-room acceptance remains pending microphone authorization.
The persistence model and phase boundaries are documented in [docs/POLL_P1.md](docs/POLL_P1.md).

Persistence includes Poll, Option, EligibleVoter, Ballot and BallotSelection.
Business voter identity remains meeting_participants.id. V14 enforces one OPEN
Poll per meeting, unique voter ballots, roster membership, same-meeting voters
and same-Poll selections. Draft roster entries become explicit snapshots at
opening in P2. Existing participant removal remains soft removal.

Existing Meeting/Participant entities and Capture/STT/Minutes/FastAPI processing
remain unchanged. MeetingServiceImpl adds Poll closure to terminal transitions,
preserving the original end permission and capture ordering.
P2 preserves existing meeting permissions while narrowly allowing authenticated
Poll routes through the security chain; Poll policy decides management access.
Next: connected-room acceptance (toolbar/chat/documents/fullscreen/mobile) and
deployment/migration in the intended environment after that acceptance. Evidence:
[docs/POLL_IMPLEMENTATION.md](docs/POLL_IMPLEMENTATION.md).

Change log — 2026-10-05: Implemented Poll P2/P3 APIs and P7 terminal integration;
79 new Poll tests passed against the 449-test baseline. Full Java 528 tests retains
two original message assertion failures, zero errors/skips. Real management UI,
member-to-manager polling and canonical end verified on isolated data; owned schemas,
servers and LiveKit test room cleaned. No application DB migration/deployment.

## 2.1 Runtime

```text
Java 17
Spring Boot 3.4.x
PostgreSQL
```

Security:
- CSRF enabled.
- Internal/service endpoints must follow current security rules.

## 2.2 Meeting roles

Current roles:

```text
CHAIRMAN
SECRETARY
MEMBER
GUEST
```

Dynamic RBAC is deferred.

## 2.3 Documents

Backend document module is implemented.

Document source values:

```text
BEFORE_MEETING
IN_MEETING
AFTER_MEETING
```

Files are stored physically according to backend storage configuration; DB stores metadata/path.

Documents are not part of the current STT pipeline.

## 2.4 Chat persistence

Implemented.

Important concepts:
- `meeting_chat_messages`
- participant connection
- `chat_token_hash`
- server-generated timestamp
- idempotency using `(meeting_id, client_message_id)`

Join flow returns raw `chatWriteToken`; only hash is stored.

Chat POST:
- resolves participant from token
- FE cannot spoof sender
- message type currently TEXT
- Chat is stored long-term in PostgreSQL

Chat repository already supports chronological retrieval by meeting.

### Agreed role in Minutes

Chat is NOT sent to Python during STT.

After Voice Transcript is available:

```text
Voice Transcript
       +
meeting_chat_messages
       ↓
Java merge by timestamp
       ↓
Meeting Timeline
```

---

# 3. Capture session metadata

Java tracks capture sessions with fields such as:

```text
externalSessionId
status
startedAt
completedAt
manifestPath
```

Minutes precondition logic can retrieve multiple completed capture sessions.

A meeting may therefore have:

```text
Meeting
├── Capture Session A → manifest A
├── Capture Session B → manifest B
└── Capture Session C → manifest C
```

This is why Python meeting-level STT accepts multiple manifest paths.

---

# 4. Minutes APIs

Current Minutes APIs include:

```http
GET  /api/minutes
GET  /api/minutes/summary

GET  /api/meetings/{id}/minutes/current
GET  /api/meetings/{id}/minutes/versions
GET  /api/meetings/{id}/minutes/versions/{version}

POST /api/meetings/{id}/minutes/versions
PUT  /api/meetings/{id}/minutes/versions/{version}
POST /api/meetings/{id}/minutes/versions/{version}/finalize

POST /api/meetings/{id}/minutes/generate
GET  /api/meetings/{id}/minutes/processing
POST /api/meetings/{id}/minutes/retry
```

## Current generate behavior

```text
POST /minutes/generate
        ↓
authorization
        ↓
meeting/capture preconditions
        ↓
anti duplicate active job
        ↓
create meeting_minutes_jobs
        ↓
status = QUEUED
stage = VALIDATING
progress = 0
        ↓
HTTP 202
```

This is already implemented.

### Current missing part

There is not yet a full Minutes background processor that moves:

```text
QUEUED
→ PROCESSING
→ Python STT
→ Transcript
→ LLM
→ Minutes V1
```

---

# 5. Important architectural decision: do not block HTTP during STT

Never implement:

```text
POST /minutes/generate
→ call Python
→ wait many minutes for STT
→ return
```

Correct flow:

```text
FE POST generate
      ↓
Java creates Minutes Job
      ↓
202 immediately
      ↓
Java background processor
      ↓
POST Python async STT job
      ↓
Python returns processingId / 202
      ↓
connection closes
```

Long STT processing happens independently.

---

# 6. Target Java ↔ Python contract

Python meeting-level job target:

```http
POST /api/postprocessing/stt/jobs
```

Body:

```json
{
  "meetingId": 12,
  "manifestPaths": [
    "meeting-12/session-a/manifest.json",
    "meeting-12/session-b/manifest.json"
  ]
}
```

Response:

```json
{
  "processingId": "...",
  "meetingId": 12,
  "status": "QUEUED"
}
```

Internal security:

```text
X-Internal-Api-Key
```

Java should send the configured internal key when Python requires it.

Python does not need Chat in this request.

Python STT progress callback accepted by Java:

```http
POST /internal/api/minutes/stt/jobs/{processingId}/progress
```

Header:

```text
X-Internal-Api-Key
```

Body:

```json
{
  "meetingId": 12,
  "processedChunks": 1,
  "totalChunks": 3,
  "progressPercent": 33.33
}
```

Response:

```text
204 No Content
```

CSRF is exempt only for:

```text
/internal/api/minutes/stt/jobs/*/progress
```

Python STT completion callback accepted by Java:

```http
POST /internal/api/minutes/stt/jobs/{processingId}/completion
```

Header:

```text
X-Internal-Api-Key
```

Body:

```json
{
  "meetingId": 12,
  "status": "COMPLETED",
  "totalManifests": 2,
  "totalSegments": 3,
  "totalChunks": 3,
  "processedChunks": 3,
  "succeededChunks": 3,
  "failedChunks": 0,
  "errorMessage": null
}
```

Terminal STT statuses:

```text
COMPLETED
PARTIAL
FAILED
```

Response:

```text
204 No Content
```

CSRF is exempt only for:

```text
/internal/api/minutes/stt/jobs/*/completion
```

Python STT result fetch supported by Java:

```http
GET /api/postprocessing/stt/jobs/{processingId}/result
```

Header:

```text
X-Internal-Api-Key
```

Response shape:

```json
{
  "processingId": "...",
  "result": {
    "meetingId": 12,
    "sessionIds": ["session-a"],
    "status": "COMPLETED",
    "totalManifests": 1,
    "totalSegments": 1,
    "totalChunks": 1,
    "processedChunks": 1,
    "succeededChunks": 1,
    "failedChunks": 0,
    "transcripts": [],
    "failures": []
  }
}
```

Java validates:
- `processingId`
- `meetingId`
- terminal status
- aggregate counts
- `transcripts.size == succeededChunks`
- `failures.size == failedChunks`

Transcript datetime fields remain transport strings:
- `segmentStartedAt`
- `absoluteStartTime`

Blank transcript text is allowed.

No DB persistence happens during result fetch yet.

---

# 7. Minutes processing stages

Agreed Java-facing stages:

```text
VALIDATING
PREPARING_AUDIO
TRANSCRIBING
BUILDING_TRANSCRIPT
GENERATING_MINUTES
COMPLETED
FAILED
```

Suggested job progress mapping:

```text
VALIDATING              0 → 5
PREPARING_AUDIO         5 → 10
TRANSCRIBING           10 → 70
BUILDING_TRANSCRIPT    70 → 80
GENERATING_MINUTES     80 → 95
COMPLETED                   100
```

Python STT progress is internally:

```text
processedChunks / totalChunks * 100
```

Java maps Python STT ratio into the global `10 → 70` range.

Example:

```text
Python STT = 50%
Java Minutes progress = 10 + (0.5 × 60) = 40%
```

FE only reads Java progress.

Implemented STT callback mapping:

```text
mappedProgress = 10 + round(pythonProgressPercent * 0.60)
clamped to 10..70
```

Progress is monotonic. Older/out-of-order callbacks never reduce
`meeting_minutes_jobs.progress_percent`.

STT progress callbacks update only jobs in:

```text
status = PROCESSING
stage  = TRANSCRIBING
```

Python STT 100% leaves Java at:

```text
PROCESSING / TRANSCRIBING / 70
```

Completion remains future work.

Implemented STT completion behavior:

```text
Python COMPLETED -> Java PROCESSING / BUILDING_TRANSCRIPT / at least 70
Python PARTIAL   -> Java PROCESSING / BUILDING_TRANSCRIPT / at least 70
Python FAILED    -> Java FAILED
```

Java persists STT terminal metadata on `meeting_minutes_jobs`:

```text
stt_status
stt_total_manifests
stt_total_segments
stt_total_chunks
stt_processed_chunks
stt_succeeded_chunks
stt_failed_chunks
stt_error_message
```

Completion callback behavior:
- Lookup uses `stt_processing_id`.
- Unknown `processingId` returns 404 so Python can retry completion later.
- Duplicate callback with the same terminal status is idempotent.
- Conflicting terminal status returns 409.
- Java can fetch and validate Python result, but does not persist it yet.

---

# 8. Target background processing algorithm

Future Java processor:

```text
meeting_minutes_jobs: QUEUED
        ↓
claim job
        ↓
PROCESSING
        ↓
VALIDATING
        ↓
load all COMPLETED capture sessions
        ↓
collect manifestPaths[]
        ↓
PREPARING_AUDIO
        ↓
POST Python /stt/jobs
        ↓
store Python processingId
        ↓
TRANSCRIBING
```

Then Python runs independently.

Production direction discussed:
- Prefer Python → Java progress callback.
- Prefer Python → Java completion callback.
- FE continues polling Java only.

Alternative fallback:
- Java can poll Python status/result APIs.

Do not hold a single Java↔Python HTTP request open for the entire transcription duration.

---

# 9. Transcript persistence target

Not implemented yet.

Java should ultimately persist Voice Transcript items with enough identity/timing fields:

```text
meetingId
captureSession/sessionId
attendeeId
speakerName
segmentId
segmentIndex
chunkIndex
chunkStartMs
chunkDurationMs
segmentStartedAt
absoluteStartTime
filePath/reference
text
```

Original STT transcript should be treated as source data.

---

# 10. Voice + Chat timeline

After STT completion:

```text
Java persists Voice Transcript
        ↓
query meeting_chat_messages
        ↓
normalize both sources
        ↓
sort by timestamp
        ↓
Meeting Timeline
```

Example:

```text
08:01:10 VOICE A: Xin chào...
08:02:15 CHAT  A: Tôi gửi tài liệu...
08:03:22 VOICE B: Tôi đồng ý...
08:06:30 CHAT  B: Đồng ý.
```

Chat should not be transcribed again.

Current implementation:
- Java transcript persistence is DONE and produces VOICE source rows in `meeting_transcript_chunks`.
- VOICE + CHAT timeline derivation is added as read-only in-memory data.
- No timeline DB table exists.
- VOICE rows are selected by `minutesJobId`; CHAT rows are selected by `meetingId`.
- VOICE `absoluteStartTime`/`segmentStartedAt` must have `Z` or explicit offset and normalize to `Instant`.
- CHAT `sentAt` is a server `LocalDateTime` interpreted with `meeting.timeline.zone-id`.
- Default timeline zone is `Asia/Ho_Chi_Minh`; timeline code does not use `ZoneId.systemDefault()`.
- VOICE and CHAT share attendee identity through `account:*` and `guest:*` attendeeId values.
- No fuzzy speaker-name matching is performed.
- Blank semantic VOICE/CHAT content is excluded from timeline output only; raw source rows are untouched.
- Overlapping VOICE chunks are preserved without dedupe, concat, or merge by speaker.
- Sort is deterministic: `occurredAt`, source priority with VOICE before CHAT, `sourceOrder`, then `sourceId`.
- Building the timeline does not change job state, stage, progress, transcript status, Python, LLM, or Minutes V1.
- NEXT is feeding `MeetingTimelineResult` to LLM generation.

---

# 11. LLM → Minutes

Not implemented yet.

Target:

```text
Meeting Timeline
      ↓
LLM
      ↓
Structured Minutes
      ↓
Java saves Minutes Version 1
```

Expected content:

```text
1. Thông tin cuộc họp
2. Thành phần tham dự
3. Nội dung cuộc họp
4. Kết luận
5. Công việc cần thực hiện
```

Java owns:
- version
- DRAFT/FINAL
- history
- editing
- persistence

LLM must not write DB directly.

---

# 12. Existing older AI flow

Source has/has had older AI orchestration classes for realtime-style AI.

Important rule:

**Do not reuse the old realtime AI flow for post-meeting STT Minutes processing unless explicitly redesigned.**

New Minutes architecture is:

```text
meeting ended
→ capture complete
→ user clicks Generate
→ post-processing job
→ STT
→ transcript
→ LLM
```

not realtime AI participation during the meeting.

---

# 13. Known constraints

- Java is the business source of truth.
- One active Minutes processing job per meeting.
- Multiple completed capture sessions may belong to one meeting.
- Scheduled meeting end should not automatically be treated as actual end in the final lifecycle design.
- Chat remains in PostgreSQL and is merged only after Voice Transcript is available.
- FE must never poll Python directly.
- Do not fake progress.
- Do not call Python once per WAV or once per manifest from Java.
- Java should call Python once per meeting-level STT job.

---

# 14. Current status

```text
Meeting core                                ✅
Documents                                   ✅
Chat persistence                            ✅
Capture session metadata                    ✅
Minutes/version API                         ✅
Minutes job creation                        ✅
QUEUED / VALIDATING                         ✅
Multiple completed capture sessions         ✅

Minutes background worker                   ❌ NEXT after Python contract
Python meeting-level STT client              ❌
Python progress integration                  ❌
Transcript persistence                      ✅
Voice + Chat timeline                        ✅
Full minutes LLM generation                 ❌
AI Result conclusion                        ✅
Minutes V1 generation                       ❌
```

---

# 15. Next work

After Python Multi-manifest / meeting-level STT is finalized:

Done in Java:
- Background worker, Python meeting-level STT client, progress callback endpoint, and completion callback endpoint.
- STT progress updates Java Minutes progress from 10 to 70 while staying in TRANSCRIBING.
- STT completion moves successful/partial jobs to BUILDING_TRANSCRIPT at 70 or marks failed jobs FAILED.
- Java STT result transport DTOs, `SttClient.getResult`, and `MeetingMinutesSttResultFetchService`.

Todo:
1. Feed `MeetingTimelineResult` to LLM generation.
2. Save Minutes V1.
3. Mark job COMPLETED 100%.

---

# 16. Codex update template

After modifying this repository, append/update:

```text
## Change log

### YYYY-MM-DD — <task>

Files added:
- ...

Files modified:
- ...

DB migration:
- ...

Endpoints added/changed:
- ...

Processing behavior:
- ...

Python contract changed:
- ...

Tests:
- ...

Next:
- ...
```

Do not document Python internals unless they affect Java's contract.

## Change log

### 2026-08-26 - Java STT progress callback

Files added:
- `src/main/java/vn/cusc/meetingservice/dto/stt/callback/SttProgressCallbackRequest.java`
- `src/main/java/vn/cusc/meetingservice/controller/InternalSttProgressCallbackController.java`

Files modified:
- `src/main/java/vn/cusc/meetingservice/repository/MeetingMinutesJobRepository.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateService.java`
- `src/main/java/vn/cusc/meetingservice/config/SecurityConfig.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateServiceTest.java`
- `src/test/java/vn/cusc/meetingservice/controller/AuthSecurityTest.java`
- `PROJECT_CONTEXT.md`

DB migration:
- None.

Endpoints added/changed:
- Added `POST /internal/api/minutes/stt/jobs/{processingId}/progress`.

Processing behavior:
- Internal callback validates `X-Internal-Api-Key`.
- Job lookup uses `stt_processing_id` with pessimistic write lock.
- Updates only `PROCESSING / TRANSCRIBING` jobs.
- Maps Python STT progress 0..100 to Java Minutes progress 10..70.
- Progress remains monotonic.

Python contract changed:
- Java now accepts Python STT progress callbacks.

Tests:
- `mvn '-Dtest=MeetingMinutesSttJobStateServiceTest,AuthSecurityTest' test`
- PASS: 23 tests, 0 failures, 0 errors.

Next:
- Python completion/result handoff, transcript persistence, Voice + Chat merge, LLM, Minutes V1.

### 2026-08-26 - Java STT completion callback

Files added:
- `src/main/java/vn/cusc/meetingservice/dto/stt/callback/SttCompletionCallbackRequest.java`
- `src/main/java/vn/cusc/meetingservice/model/enums/SttTerminalStatus.java`
- `src/main/java/vn/cusc/meetingservice/controller/InternalSttCompletionCallbackController.java`
- `src/main/resources/db/migration/V10__add_stt_completion_metadata_to_minutes_jobs.sql`

Files modified:
- `src/main/java/vn/cusc/meetingservice/entity/MeetingMinutesJobEntity.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateService.java`
- `src/main/java/vn/cusc/meetingservice/config/SecurityConfig.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateServiceTest.java`
- `src/test/java/vn/cusc/meetingservice/controller/AuthSecurityTest.java`
- `PROJECT_CONTEXT.md`

DB migration:
- Added nullable STT completion summary columns to `meeting_minutes_jobs`.

Endpoints added/changed:
- Added `POST /internal/api/minutes/stt/jobs/{processingId}/completion`.

Processing behavior:
- Completion validates `X-Internal-Api-Key`, meetingId, terminal status, and count invariants.
- COMPLETED/PARTIAL moves Java job to `PROCESSING / BUILDING_TRANSCRIPT / 70`.
- FAILED persists STT summary and marks Java Minutes job FAILED.
- Unknown `processingId` returns 404.
- Duplicate same terminal status is idempotent; conflicting terminal status returns 409.
- No Python result fetch or transcript persistence yet.

Python contract changed:
- Java now accepts Python STT terminal completion callbacks.

Tests:
- `mvn '-Dtest=MeetingMinutesSttJobStateServiceTest,AuthSecurityTest' test`
- PASS: 38 tests, 0 failures, 0 errors.

Next:
- Python completion sender, E2E completion test, Java result handoff, transcript persistence, Voice + Chat merge, LLM, Minutes V1.

### 2026-08-26 - Java STT result fetch

Files added:
- `src/main/java/vn/cusc/meetingservice/dto/stt/response/SttJobResultResponse.java`
- `src/main/java/vn/cusc/meetingservice/dto/stt/response/SttMeetingResultResponse.java`
- `src/main/java/vn/cusc/meetingservice/dto/stt/response/SttTranscriptChunkResponse.java`
- `src/main/java/vn/cusc/meetingservice/dto/stt/response/SttFailedChunkResponse.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttResultFetchService.java`
- `src/test/java/vn/cusc/meetingservice/client/stt/SttClientImplTest.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttResultFetchServiceTest.java`

Files modified:
- `src/main/java/vn/cusc/meetingservice/client/stt/SttClient.java`
- `src/main/java/vn/cusc/meetingservice/client/stt/SttClientImpl.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateService.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateServiceTest.java`
- `PROJECT_CONTEXT.md`

DB migration:
- None.

Endpoints added/changed:
- Java client supports `GET /api/postprocessing/stt/jobs/{processingId}/result`.

Processing behavior:
- `requireResultFetchContext(jobId)` reads and validates Java job state in a read-only transaction.
- `fetchResult(jobId)` calls Python outside the DB transaction.
- Python result is validated against persisted completion metadata.
- Blank transcript text is allowed.
- Datetime strings are preserved as transport values.
- No transcript persistence or scheduler result fetch yet.

Python contract changed:
- Java now maps and validates Python STT result response.

Tests:
- `mvn '-Dtest=SttClientImplTest,MeetingMinutesSttResultFetchServiceTest,MeetingMinutesSttJobStateServiceTest,MeetingMinutesSttSubmissionServiceTest,MeetingMinutesJobDispatcherTest' test`
- PASS: 65 tests, 0 failures, 0 errors.

Next:
- Java transcript persistence will call `MeetingMinutesSttResultFetchService.fetchResult(jobId)` and then persist transcripts in the same processing flow.

### 2026-08-26 - Java STT result persistence

Files added:
- `src/main/java/vn/cusc/meetingservice/model/enums/TranscriptPersistenceStatus.java`
- `src/main/java/vn/cusc/meetingservice/entity/MeetingTranscriptChunkEntity.java`
- `src/main/java/vn/cusc/meetingservice/entity/MeetingTranscriptFailureEntity.java`
- `src/main/java/vn/cusc/meetingservice/repository/MeetingTranscriptChunkRepository.java`
- `src/main/java/vn/cusc/meetingservice/repository/MeetingTranscriptFailureRepository.java`
- `src/main/java/vn/cusc/meetingservice/exception/SttResultValidationException.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesTranscriptStateService.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesTranscriptPersistenceService.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesTranscriptDispatcher.java`
- `src/main/resources/db/migration/V11__create_meeting_transcript_storage.sql`

Files modified:
- `src/main/java/vn/cusc/meetingservice/entity/MeetingMinutesJobEntity.java`
- `src/main/java/vn/cusc/meetingservice/repository/MeetingMinutesJobRepository.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateService.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesSttResultFetchService.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttJobStateServiceTest.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesSttResultFetchServiceTest.java`
- `PROJECT_CONTEXT.md`

DB migration:
- Added transcript persistence status columns to `meeting_minutes_jobs`.
- Added `meeting_transcript_chunks` and `meeting_transcript_failures`.
- Backfills eligible `BUILDING_TRANSCRIPT` jobs to transcript `PENDING`, and failed STT jobs to transcript `FAILED`.

Processing behavior:
- Completion `COMPLETED`/`PARTIAL` now initializes transcript work as `PENDING`.
- New transcript dispatcher finds one pending `PROCESSING / BUILDING_TRANSCRIPT` job per tick.
- Claim uses `findByIdForUpdate`, increments transcript attempts, commits before Python GET.
- `MeetingMinutesTranscriptPersistenceService` calls `fetchResult(jobId)` outside the write transaction.
- Persist writes transcript chunks/failures and marks transcript `READY` atomically, with progress at least 80.
- Python result validation mismatches now throw `SttResultValidationException`; HTTP/client failures remain `AiServiceException`.

Tests:
- `mvn '-Dtest=MeetingMinutesSttJobStateServiceTest,MeetingMinutesSttResultFetchServiceTest,MeetingMinutesTranscriptStateServiceTest,MeetingMinutesTranscriptPersistenceServiceTest,MeetingMinutesTranscriptDispatcherTest' test`
- PASS: 81 tests, 0 failures, 0 errors.

Next:
- Voice + Chat timeline merge, LLM, Minutes V1.

### 2026-08-27 - Voice + Chat Meeting Timeline

Files added:
- `src/main/java/vn/cusc/meetingservice/config/MeetingTimelineProperties.java`
- `src/main/java/vn/cusc/meetingservice/model/enums/MeetingTimelineSourceType.java`
- `src/main/java/vn/cusc/meetingservice/dto/timeline/MeetingTimelineItem.java`
- `src/main/java/vn/cusc/meetingservice/dto/timeline/MeetingTimelineResult.java`
- `src/main/java/vn/cusc/meetingservice/service/MeetingMinutesTimelineService.java`
- `src/test/java/vn/cusc/meetingservice/service/MeetingMinutesTimelineServiceTest.java`

Files modified:
- `src/main/java/vn/cusc/meetingservice/repository/MeetingTranscriptChunkRepository.java`
- `src/main/java/vn/cusc/meetingservice/repository/MeetingChatMessageRepository.java`
- `src/main/resources/application.yml`
- `PROJECT_CONTEXT.md`

DB migration:
- None.

Endpoints added/changed:
- None.

Processing behavior:
- Added read-only `MeetingMinutesTimelineService.buildTimeline(jobId)`.
- Builds `MeetingTimelineResult` from VOICE transcript chunks and CHAT messages.
- VOICE is selected by `minutesJobId`; CHAT is selected by `meetingId`.
- VOICE timestamps require offset/Z and normalize to `Instant`.
- CHAT `LocalDateTime` uses `meeting.timeline.zone-id`, default `Asia/Ho_Chi_Minh`.
- No `ZoneId.systemDefault()` is used.
- Shared attendee identity comes from `attendeeId`; no fuzzy name matching.
- Blank semantic content is excluded only from the timeline; DB source rows remain untouched.
- Overlapping Voice chunks are retained.
- Sort is deterministic: occurredAt, source priority, sourceOrder, sourceId.
- No job state, progress, transcript status, Python, LLM, or Minutes V1 behavior changed.

Python contract changed:
- None.

Tests:
- `mvn '-Dtest=MeetingMinutesTimelineServiceTest' test`
- `mvn '-Dtest=MeetingMinutesTranscriptStateServiceTest,MeetingMinutesTranscriptPersistenceServiceTest,MeetingMinutesTranscriptDispatcherTest' test`
- `mvn '-Dtest=MeetingMinutesSttResultFetchServiceTest,MeetingMinutesSttJobStateServiceTest,SttClientImplTest' test`

Next:
- Feed `MeetingTimelineResult` to LLM generation; no LLM implementation in this task.

### 2026-10-05 — Poll P1 domain/persistence

- Added Flyway V14 with five Poll tables and composite/unique/check constraints.
- Added five lazy JPA entities, four enums and five scoped/ordered repositories.
- Added H2 repository tests and opt-in PostgreSQL migration/integrity/concurrency
  tests using a fresh disposable schema. No new dependencies or application
  configuration changes.
- P1 tests: 41 PASS (9 H2, 32 PostgreSQL). Final Java run: 490 tests,
  488 PASS, the same 2 baseline message-assertion failures, 0 errors, 0 skipped.
- Existing application sources are unchanged. Application DB has not been
  migrated to V14; the test schema was removed.
- Details and rollout/rollback boundaries: [docs/POLL_P1.md](docs/POLL_P1.md).
- Next Poll phase: P2 lifecycle/authorization and explicit eligible snapshot.

### 2026-10-08 — Production audit 24F remediation

- This authorized remediation preserves the user's final PUBLIC/PRIVATE/POINT and creator/officer business policies; email and extraction into three independent Java services remain deferred.
- Added V20/V21/V22 for durable media/file cleanup, document retry dedup, webhook ordering, minutes revisions and account credential epochs/optimistic account writes. Historical migration files remain unchanged.
- Join provisions LiveKit outside the meeting write transaction, then locks/revalidates before issuing credentials. All-mode UUID creation receipts, atomic roster/baseline edits, canonical persisted chat/history, terminal capture/poll/speaking finalization, scheduled-expiry reconciliation and ordered terminal-safe webhooks are now implemented.
- Capture ACK/failure writes and recovery use the common meeting lock before fresh session loading. Definitively failed recordings retain FAILED/partial evidence and can be finalized without a false successful-recording claim.
- Account state/epoch is refreshed before URL authorization; manual login rotates JDBC Session and CSRF. PUBLIC guest ownership requires an opaque resume proof. Document content inspection, staged IO, safe attachment/preview delivery and durable cleanup replace MIME trust and destructive precommit file deletion.
- Draft PUT requires expectedRevision; updated/finalized receipts flush before exposing revision. Bounds, admission limits, provider call timeouts and production key guards are enforced. Existing menus, open Q&A/polls and meeting role policy remain unchanged.
- Release contracts/limits: [docs/AUDIT24_RELEASE.md](docs/AUDIT24_RELEASE.md). Verification status and exact logs: `D:/DuAn/Livekit/output/audit24/STATUS.md`; no production deployment or database modification occurred.
- Final review also closed admin cancellation across capture/poll/speaking, nullable quick-meeting schedules, and concurrent rejected-join cleanup versus explicit admin reopen. Reopen waits for old media cleanup. Generated times use PostgreSQL microsecond precision; already-started dateless rooms keep their state during clock correction.
