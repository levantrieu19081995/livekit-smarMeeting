# Poll implementation gates — P2 through P7

Started 2026-10-05. Each phase is reviewed and tested before continuing.
The baseline contains two existing message assertions failing in MeetingServiceTest.
They remain recorded failures; they are not skipped, rewritten or counted as PASS.
The regression gate is no additional failures/errors relative to that baseline.

| Phase | Status | Evidence |
| --- | --- | --- |
| P1 | Implemented/tested | 41 Poll tests passed; full Java 490 tests, 2 baseline failures, 0 errors |
| P2 | Implemented/tested | 15 lifecycle/authorization tests and 4 real security-chain/session/CSRF tests passed; full Java 509 tests, 2 baseline failures, 0 errors, 0 skipped |
| P3 | Implemented/tested | 11 vote/visibility/restore tests passed; full Java 520 tests, 2 baseline failures, 0 errors, 0 skipped |
| P4 | Implemented/tested | Typed client using existing session/CSRF proxy; manager editor and room toolbar; 6 client HTTP/CSRF tests; 86 frontend tests passed |
| P5 | Implemented/tested | Participant ballot, confirmed save/change and persisted restore; 4 ballot tests; 90 frontend tests passed |
| P6 | Implemented/tested | Polling, stale-response rejection, timeout, closed results and real history; 7 coordinator/result tests; 97 frontend tests passed |
| P7 | Implemented; room acceptance pending | 8 real PostgreSQL runtime/HTTP/race tests passed; full Java 528 tests, 2 baseline failures, 0 errors, 0 skipped; 210 FastAPI tests passed; frontend types/build/tests passed; real management UI tested |

## P2 contract

- Poll routes require authentication and existing CSRF protection. A narrow Poll
  route matcher precedes the existing generic meeting PUT matcher. Existing
  meeting-management permissions are unchanged.
- Management policy: ADMIN, meeting creator, active CHAIRMAN/SECRETARY.
  MEETING_MANAGER alone grants viewing, not Poll management of another creator's meeting.
- Active authenticated meeting participants can view published Polls; outsiders,
  anonymous users and removed participants cannot. Drafts and voter keys are
  restricted to Poll managers.
- Draft configuration accepts only eligible non-GUEST authenticated participants
  from the same meeting. Opening atomically snapshots the explicit roster.
- YES_NO_ABSTAIN canonical choices are server-generated. Generic choices require
  2–20 distinct nonblank labels and valid min/max selection limits.
- DRAFT editing only; no opening outside effective IN_PROGRESS; terminal/effectively
  completed meetings are read-only. Opening/closing/cancelling replays are idempotent.
- Every write takes the existing Meeting aggregate lock first, then the scoped
  Poll lock. No optimistic locking, WebSocket or alternate business state is added.

## API

- GET /api/meetings/{meetingId}/polls/context
- GET /api/meetings/{meetingId}/polls?page=0&size=20
- GET /api/meetings/{meetingId}/polls/active (204 if none)
- GET /api/meetings/{meetingId}/polls/{pollId}
- POST /api/meetings/{meetingId}/polls
- PUT /api/meetings/{meetingId}/polls/{pollId}
- POST /api/meetings/{meetingId}/polls/{pollId}/open
- POST /api/meetings/{meetingId}/polls/{pollId}/close
- POST /api/meetings/{meetingId}/polls/{pollId}/cancel

V14 has not been applied to the application schema. Runtime verification uses
isolated test data. FastAPI/Capture/STT/Minutes contracts remain frozen.

## P3 contract

- PUT /api/meetings/{meetingId}/polls/{pollId}/my-vote accepts optionIds only.
  Principal/account-to-participant resolution determines the voter.
- Meeting-then-Poll locks serialize vote/change/close operations; DB constraints
  remain the final defense. Snapshot membership and option/cardinality are checked.
- Identical selection-set retries return the original persisted ballot, including
  after closing; new/changed votes require an effective live meeting and OPEN Poll.
- allowChangeVote=false rejects different selections. When enabled, replacing
  selections preserves one ballot and the original submittedAt.
- myVote restores persisted selections/timestamps. API results honor LIVE,
  AFTER_CLOSE (including managers) and AUTHORIZED_ONLY. Hidden results are null,
  not a client-hidden aggregate. Progress counts contain no per-option counts.
- Percentages use submitted ballots as the denominator. Multiple-choice totals
  may exceed 100%; this is not a percentage of all eligible voters.
- Late join does not change the electorate. Soft-removal revokes further access
  without deleting saved votes or the snapshot denominator.

## P4–P6 contract

- A Poll button opens the scoped panel beside LiveKit. Poll/documents/chat panels
  share the existing layout; no Room recreation, capture start/stop or media setting
  change is introduced. Fullscreen/mobile use the existing side-panel rules.
- The same panel appears in meeting detail with persisted Poll history, separate
  from the existing demo activity timeline. Nonmanagers receive published Polls only.
- The editor configures the explicit electorate, type, limits, visibility and vote
  changes. The server generates canonical YES/NO/ABSTAIN choices.
- Ballot controls use server canVote. Selection limits are validated locally and
  again on the server. There is no optimistic saved-vote or aggregate update.
- Successful mutations apply the confirmed response then refresh. Failed/ambiguous
  submissions keep an error until the user requests a refresh; no automatic retry.
- Visible panels refresh active/final Poll data every 3 seconds. Full history/context
  refresh every 15 seconds, immediately after actions and on focus/online/visibility.
  Hidden panels pause periodic reads; unmount clears listeners/timers and invalidates
  responses. Requests expire after 12 seconds; mutations after 30 seconds.
- Old GETs cannot overwrite newer reads or confirmed writes. Unauthorized responses
  clear cached Poll data. Auth identity/meeting changes reset the panel.
- Saved selections restore by persisted vote revision, preserving unsent choices
  during ordinary background refreshes. Closed history also displays the caller's vote.
- Result percentages are rendered from the server. Multiple-choice denominator and
  potentially >100% total are explained. Closed results remain visible when active=204.

## P7 evidence and acceptance boundary

- Canonical endMeeting retains its original Meeting lock, permission check and
  capture finalization ordering. Poll closure joins the same transaction after capture
  finalization and before terminal Meeting persistence/LiveKit cleanup. The existing
  CANCELLED update path also closes an OPEN Poll; accepted ballots remain saved.
- Closure is idempotent and uses Meeting-then-Poll locking. A SQL transaction rollback
  restores both Poll and Meeting. Existing external LiveKit cleanup semantics remain
  unchanged; the SQL rollback test does not claim to undo external side effects.
- Poll read endpoints use REPEATABLE_READ so roster, progress and per-option counts
  are computed against one PostgreSQL snapshot within each response.
- MeetingPollRuntimeTest uses a random `poll_p7_test_<uuid>` schema, real Spring Boot
  HTTP server, real login/password/session/CSRF, services and PostgreSQL. Only external
  Capture/LiveKit collaborators are mocked in this suite. Tests cover vote-vs-close,
  duplicate vote, vote-vs-end, rollback, capture failure, cancellation, concurrent
  aggregate reads, and HTTP vote/re-login/restore/close/results/access.
- Browser verification used separate local ports 3009/8089 and a random
  `poll_ui_test_<uuid>` schema. A uniquely named real LiveKit room was created.
  The actual management UI created/opened/voted/reloaded/closed a canonical Poll and
  created a multiple-choice Poll with min=max=2, checked disabled submission with one
  choice, changed the saved vote, and showed one voter with server percentages.
  A MEMBER account then voted through the API and the manager UI updated to two
  voters without reloading. The real canonical end endpoint returned 204, persisted
  COMPLETED/CLOSED, preserved both ballots and removed the uniquely named LiveKit
  room. After reloading, both closed Polls and the caller's votes/results remained.
  Meeting-detail history was also verified at 390px without horizontal overflow.
- Room entry was stopped at the existing microphone prerequisite. The user has been
  asked whether microphone use is allowed. Toolbar behavior inside a connected room,
  chat/documents/fullscreen/mobile interactions and live media/capture end-to-end
  acceptance are **not yet certified**. P7 is not marked fully accepted.
- No application-schema migration or deployment was performed. The new tables are
  still absent from public/application schema; runtime tests use only owned schemas.
  Cleanup verified zero leftover Poll test schemas and zero public Flyway V14 entries.
  Test servers were stopped; LiveKit API confirmed the owned test room no longer exists.
- Two unrelated room header/error source edits appeared during this run and were
  preserved. Poll work does not claim ownership of or revert those edits.

## Reproduce checks

From meeting-service, set POLL_TEST_DB_PASSWORD for your local PostgreSQL and run
`mvn -Dpoll.postgres.url=jdbc:postgresql://127.0.0.1:5432/meeting_db test`.
The PostgreSQL suites create and drop only randomly named test schemas. Without the
explicit PostgreSQL property these suites are skipped and cannot certify real DB behavior.
This environment uses offline Maven and `-Dmaven.repo.local=D:\DuAn\Livekit\.m2`.

From meeting-frontend run `npm test`, `tsc --noEmit --incremental false` and `npm run build`.
The existing `.next/trace` was inaccessible; the verified build used NEXT_DIST_DIR
`.next-poll-verification`. Existing non-Poll lint/CSS warnings remain recorded.
From meeting-fastapi run `python -m pytest -q -p no:cacheprovider` with an owned temp directory.

Logs and screenshot are under meeting-service/target: poll-p7-full.log,
poll-p7-frontend.log, poll-p7-types.log, poll-p7-build.log, poll-p7-fastapi.log,
and poll-p7-ui-results.jpg. Build/test tools may need normal child-process/temp-directory
permissions on Windows. A blocked or failed invocation is not counted as a PASS.
