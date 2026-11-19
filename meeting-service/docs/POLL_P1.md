# Poll P1 — domain and persistence

Implemented on 2026-10-05. P1 is the Java/PostgreSQL foundation only.
No Poll controller, lifecycle service, authorization service, voting API, scheduler,
frontend integration, FastAPI change, or end-meeting integration is included.

## Model

| Entity/table | Purpose |
| --- | --- |
| MeetingPoll / meeting_polls | Meeting-owned question, description, type, lifecycle status, selection limits, change-vote/result policies, creator, lifecycle timestamps |
| MeetingPollOption / meeting_poll_options | Ordered options; optional YES/NO/ABSTAIN code independent of translated labels |
| MeetingPollEligibleVoter / meeting_poll_eligible_voters | Explicit draft voter selection and the eventual opening snapshot |
| MeetingPollBallot / meeting_poll_ballots | One ballot per Poll/business participant, with initial submission time |
| MeetingPollBallotSelection / meeting_poll_ballot_selections | Selected options belonging to that ballot's Poll |

All entities follow the existing BaseEntity timestamps, identity-generated Long IDs,
lazy many-to-one associations and repository conventions. They have no cascade to
existing Meeting, Participant or Account entities. MeetingEntity is unchanged.
No alternate voter identity, cached tally, optimistic-lock architecture or
true-anonymous ballot model is introduced.

Enums:

- Status: DRAFT, OPEN, CLOSED, CANCELLED.
- Type: YES_NO_ABSTAIN, SINGLE_CHOICE, MULTIPLE_CHOICE.
- Result visibility: LIVE, AFTER_CLOSE, AUTHORIZED_ONLY.
- Canonical option code: YES, NO, ABSTAIN; null for generic choices.

New Polls default to DRAFT, no vote changes, AFTER_CLOSE and one selection.
Multiple-choice Polls can configure positive min/max selection limits.

## Integrity in Flyway V14

- A partial unique index permits at most one OPEN Poll per meeting.
- Unique keys reject repeated roster voters, ballots, ballot selections,
  option positions and non-null canonical option codes within a Poll.
- Composite foreign keys enforce voter/meeting consistency, eligibility and
  option/ballot/Poll consistency. Ballots also reference meeting_participants.id
  directly; they do not use LiveKit identity or a display name.
- Checks reject unknown enum values, blank questions/options, negative option
  positions, invalid selection limits, incomplete snapshots and inconsistent
  lifecycle timestamps.
- Eligible voter rows in DRAFT have null snapshot fields. P2 must fill
  participantKeySnapshot, fullNameSnapshot, roleSnapshot and snapshotAt together
  when opening, then freeze the roster. Repository counts exclude draft rows
  whose snapshotAt is null.
- Deferred NO ACTION voter/option references protect persisted votes from
  independent hard deletion. Existing participant removal marks REMOVED and
  continues to work without deleting the roster or ballot.
- Existing full meeting deletion still works: ORM may delete participants before
  the meeting; meeting deletion cascades the Poll aggregate before the deferred
  constraints are checked at commit.

V14 adds five tables and indexes. Its only change to an existing table is a
redundant unique index on meeting_participants(id, meeting_id), needed for the
same-meeting foreign key. It does not update legacy business rows or alter V1–V13.

## Boundaries for P2/P3

The schema is not a substitute for the upcoming business services. P2/P3 must:

- Resolve the current authenticated account to the business participant; never
  accept a client-supplied voter ID for my-vote.
- Apply the separate Poll management policy and exclude GUEST from v1 eligibility.
- Validate the effective meeting lifecycle and allowed Poll transitions.
- Lock Meeting first, then Poll using the scoped repository lock, consistently
  across lifecycle, voting and eventual end-meeting integration.
- Require a finalized eligible snapshot before accepting a vote.
- Validate option count, canonical option sets and selected-option cardinality.
- Freeze question/options/policies/snapshot after opening.
- Enforce allowChangeVote and idempotent replay of the same selection set.
- Apply resultVisibility in API responses. Repository data is internal, not a
  permission-filtered API.

Repositories provide scoped Poll lookup/locking, paginated deterministic history,
OPEN lookup, ordered options, roster/snapshot checks, my-ballot lookup and ordered
ballot selections. No result computation or lifecycle behavior is exposed yet.

## Validation

MeetingPollRepositoryTest exercises persistence/restore, meeting scoping,
pagination, option ordering, snapshot stability after participant edits and
duplicate constraints using the existing H2 test profile.

MeetingPollPostgresTest is opt-in because H2 cannot establish PostgreSQL partial
index or deferred-FK behavior. It creates one randomly named poll_p1_test_* schema,
migrates V1–V13, seeds legacy rows, upgrades to V14 and validates every existing
and new entity against the real schema. It tests cross-meeting/cross-Poll rejection,
constraints, aggregate cleanup and concurrent writes. It drops only its owned
schema afterward. It does not migrate the application's normal schema.

ORM cleanup covers participants and the Poll aggregate. A separate JDBC cleanup
test also covers seeded legacy Capture/Minutes rows. Existing Minutes @Lob/TEXT
read semantics are outside P1 and are not certified by this test.

Run the focused tests (PowerShell; supply credentials through the environment):

```powershell
# Set POLL_TEST_DB_PASSWORD to the test database password first.
mvn '-Dtest=MeetingPollRepositoryTest,MeetingPollPostgresTest' '-Dpoll.postgres.url=jdbc:postgresql://127.0.0.1:5432/meeting_db' '-Dpoll.postgres.username=meeting_user' test
```

Omit -Dtest for the full suite. Without poll.postgres.url the PostgreSQL class is
skipped; this is not PostgreSQL validation. The ordinary suite still runs the H2
mapping tests. A workspace-local dependency cache can be selected with
-Dmaven.repo.local=D:\DuAn\Livekit\.m2.

### Results recorded on 2026-10-05

- Baseline before P1: 449 tests; 447 passed, 2 failures, 0 errors.
- Final full Java run including real PostgreSQL: 490 tests; 488 passed,
  2 failures, 0 errors, 0 skipped.
- P1 specifically: 9 repository/mapping tests and 32 PostgreSQL tests passed.
- The same two pre-existing MeetingServiceTest failures remain:
  updateMeetingCannotTransitionToCompleted and updateMeetingCannotReopenCompletedMeeting.
  They compare accented Vietnamese expected messages with existing unaccented
  service messages; neither those tests nor that service was changed in P1.
- SHA-256 comparison checked 438 pre-existing files across Java, frontend and
  FastAPI code/tests plus the Java POM/context. Only PROJECT_CONTEXT.md changed.
- No poll_p1_test_* schema remains after validation, and the application's
  public Flyway history has no V14 entry.

The full suite is not reported as green; P1 introduces no new failure relative
to the measured baseline. Runtime Poll E2E remains a P7 obligation.

## Rollout and rollback

The application schema was not migrated during implementation. V14 runs through
the existing Flyway mechanism on a future application start/deployment.
Before deployment, preserve the database backup and run the normal migration
validation procedure.

For application rollback, the prior Java version can continue using the existing
schema while the unused additive Poll tables remain. Preserve Poll data if any
has been created. Do not edit an applied V14, remove Flyway history, or automatically
drop Poll tables; a schema rollback requires an explicit reviewed migration.
