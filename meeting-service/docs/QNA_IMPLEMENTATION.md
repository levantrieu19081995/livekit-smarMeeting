# Q&A implementation and phase acceptance

Approved scope: Google Meet-style Q&A, adapted to existing meeting roles.
No textual-answer, transcript, minutes-generation or email changes are included.

## Participation policy update (2026-10-07)

The user requested that all meeting participants can ask questions without waiting
for LiveKit to confirm CONNECTED. Room 37 reproduced the problem: the exact
account connection was ACTIVE on LiveKit but remained PENDING in the database.
Q&A now accepts the meeting-scoped token issued by join for either PENDING or
CONNECTED connections. This applies to chairman, secretary, member and guest
alike; asking/upvoting do not require a moderator role or a received webhook.
LEFT/REMOVED connections, removed participants, disabled accounts, invalid or
cross-meeting tokens, session/token mismatches and tokenless writes remain denied.
The meeting must still be IN_PROGRESS and Q&A enabled. Moderator authority,
privacy, CSRF, receipt deduplication and closed-meeting history are unchanged.
No database connection is marked CONNECTED by Q&A and no LiveKit event is forged.

## Open meeting visibility update (2026-10-07)

PUBLIC Q&A context/history can be read by any authenticated account, including
an account outside the roster. PRIVATE reads require non-removed roster
membership or a matching unbound email invitation, including for administrators
and owners. The join credential is still required for asking/upvoting; viewing
does not confer moderator authority or CSV access. Moderator actions in PRIVATE
are also subject to its visibility scope. Anonymous identities remain redacted.

## P1 rules (2026-10-06)

- Questions are independent of chat and polls. Database/API responses are the
  source of truth; user names or browser guest IDs never prove authorization.
- Defaults: Q&A enabled, anonymous questions allowed, moderation disabled.
- Asking and upvoting require a join-issued participant token belonging to a
  PENDING or CONNECTED, non-removed participant of this meeting, and IN_PROGRESS status.
  The existing join participant token is accepted only by the new Q&A policy;
  existing chat authorization and joins do not change.
- Session-authenticated administrators, owners, active chairmen and secretaries
  can moderate. Guests and ordinary members cannot moderate. Provided invalid
  tokens or session/token identity mismatches are rejected without fallback.
- Authorized signed-in viewers can read history without an active room token.
  Guest access requires its verified active connection token, including reads.
- Anonymous identity is never returned to another participant, moderator or
  report. Internal participant identity supports ownership and duplicate checks.
- A submitted question is OPEN, or PENDING if moderation is enabled. Pending
  questions are visible only to their author and moderators. Other participants
  see OPEN and ANSWERED. HIDDEN and DELETED are moderator-only records.
- Moderators approve PENDING to OPEN, mark OPEN as ANSWERED, hide PENDING/OPEN/
  ANSWERED, and delete non-deleted questions. Delete is a persisted tombstone for
  the moderator report; it removes the question from participant views.
- Turning moderation off does not approve previous PENDING questions. Switching
  anonymity off applies to new submissions; existing anonymous records stay
  anonymous. Disabling Q&A preserves its history and blocks asking/upvoting.
- Upvotes use a desired boolean, one vote per participant/question, on OPEN or
  ANSWERED questions. Repeating the same desired value is safe.
- Asking uses a participant-scoped UUID; retrying identical content/anonymity
  returns the persisted question while it remains visible to that author.
  Hidden/deleted receipts are unavailable to non-moderators. Reusing that UUID
  for different data conflicts. First receipts use database-persisted timestamps,
  so PostgreSQL precision does not change an identical retry response.
- Disabled accounts invalidate both cached session access and account tokens.
  LEFT/REMOVED connections never grant participation. A PENDING connection means
  a valid join-issued session still waiting for LiveKit confirmation; Q&A accepts
  that session without changing the connection's persisted status.
- Settings and moderator actions require an expected version. Stale moderators
  receive a conflict, reload and decide again. Writes serialize on the meeting.
- Ended/cancelled meetings are read-only. No meeting lifecycle, recording, STT or
  minutes logic is modified. Reports require moderator authorization, preserve
  anonymity and escape CSV/formula-sensitive content.
- Room change hints use the dedicated `cusc.qna.changed` topic and contain only
  the meeting ID. Anonymous asks never publish a client hint because the SDK
  exposes the packet sender; visible HTTP polling synchronizes those questions.
  Hints never grant access. Reads coalesce, stop while hidden/unmounted, recover
  on reconnect/focus/online, and cannot overwrite confirmed writes or a new identity.

## Report visibility update (2026-10-07)

- The CSV button is shown only to moderators when this meeting has at least one
  saved Q&A question. A new/empty meeting, including an empty ended meeting,
  does not show a button that would download a header-only CSV.
- A question does not need ANSWERED status to be included: the report lists
  questions, status, upvotes and timestamps; it does not contain textual answers.
- Availability uses the moderator's unfiltered ALL count. Empty MINE/PENDING
  filters or an empty current page do not hide a valid whole-meeting report.
  Filtered moderator views use a size=1 ALL read; the normal list still defaults
  to size=20. Other viewers do not make this extra read.
- Availability resets with private session state/permission loss and follows the
  existing refresh coordinator after saved questions and background changes.
  Report authorization, CSV contents and all backend APIs are unchanged.
- Verification: 35 focused frontend service/download/scheduler/coordinator tests,
  TypeScript, formatting and browser cases with local API stubs passed. Evidence:
  `target/qna-empty-report-verification/`.

## API contract

Base: `/api/meetings/{meetingId}/qa`.
Room clients send `X-Meeting-Participant-Token`; existing CSRF protections remain.

| Method | Route | Input / purpose |
| --- | --- | --- |
| GET | `/context` | Settings, meetingStatus, canManage, canAsk, canUpvote |
| GET | `/questions` | page, size (bounded), sort NEWEST/OLDEST/POPULAR, filter ALL/MINE/PENDING |
| POST | `/questions` | clientQuestionId (UUID), content (1–2000 trimmed characters), anonymous |
| PUT | `/questions/{id}/upvote` | upvoted (boolean) |
| PUT | `/settings` | enabled, anonymousAllowed, moderationEnabled, expectedVersion |
| POST | `/questions/{id}/actions` | APPROVE/ANSWER/HIDE/DELETE, expectedVersion |
| GET | `/report` | UTF-8 CSV for moderators, available after the meeting |

Question fields: id, own clientQuestionId, content, anonymous, authorDisplayName,
status, createdAt, updatedAt, answeredAt, upvoteCount, upvotedByMe, mine, version.
Anonymous authorDisplayName is always `Ẩn danh`. Neither participant, connection,
account IDs nor moderator audit identity are part of question/report responses.
Stable ordering includes the question ID as a tie breaker. Pagination and server
privacy filtering occur before returning rows/counts.

## Phase gates

Only proceed after the preceding gate has passed. Runtime claims must identify
whether they use mocks, H2 or real PostgreSQL/HTTP/LiveKit.

| Phase | Required evidence | Status |
| --- | --- | --- |
| P1 | Rules/API/privacy reviewed against current source and existing authorization | PASS: independent frontend/backend audits; contract agreed |
| P2 | Persistence, authorization, API validation, duplicate/upvote and privacy tests | PASS: 38 focused H2/security; 81 real PostgreSQL/HTTP QA+Poll regression checks |
| P3 | Participant UI and API contract tests, responsive view checks | PASS: 141 frontend tests, TypeScript; 17 browser cases incl. phones/tablets, guest retry and existing room controls |
| P4 | Moderator authorization/transitions/version conflict and UI checks | PASS: 72 H2/security; 96 real PostgreSQL/HTTP QA+Poll; 155 frontend tests, TypeScript and 9 browser cases |
| P5 | Stale request guards, reconnect/reload/retry and multi-user synchronization checks | PASS: 168 frontend tests, TypeScript; 11 browser cases; independent synchronization/privacy review |
| P6 | Read-only postmeeting history/report and anonymity/export checks | PASS: 95 H2/security/CSV; 113 real PostgreSQL/HTTP QA+Poll; 177 frontend tests, TypeScript and 8 browser cases, including completed CSV download |
| P7 | Full frontend/backend regressions, production build, DB/race and browser integration | PASS: 696 backend tests (zero skips), 177 frontend tests, TypeScript/format/build; 12 final browser cases; isolated resources cleaned |

Evidence directory: `target/qna-verification`. Keep temporary fixtures and test
logs there; do not ship test fixtures or secrets in production source.

Signed participants can open `/management/meetings/{meetingId}/qa` through the
room's history link. This route inherits the management login guard and calls
only Q&A APIs, so ordinary members do not gain generic meeting-edit permissions.
The meeting detail page also includes Q&A. Disconnected guests have no durable
history credential. Reports include six business columns only and fetch bounded
200-row projections within a consistent snapshot; CSV cells remain literal.
