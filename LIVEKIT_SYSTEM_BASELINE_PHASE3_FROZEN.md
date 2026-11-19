**# LIVEKIT SYSTEM BASELINE & CHANGE CONTRACT**

**\*\*Version:\*\*** 1.8  

**\*\*Date:\*\*** 2026-09-11  

**\*\*Audited source snapshots:\*\***

\- \`meeting-frontend(20260908-094725).zip\`

\- \`src(20260908-094741).zip\`

\- \`meeting-fastapi(20260908-094706).zip\`

**\*\*Phase status:\*\***  

\- \`PHASE 1 — FROZEN\`

\- \`PHASE 2 — FROZEN\`

\- \`PHASE 3 — FROZEN\`

\- \`PHASE 4 — FROZEN\`

\- \`PHASE 5 — FROZEN\`

\---

**## 1. Purpose**

This file is the **\*\*baseline contract\*\*** for all future changes across the 3-source LiveKit Meeting system.

It exists to prevent this failure mode:

\`\`\`text

Fix Phase 1

→ Phase 2 changes a nearby flow

→ Phase 4 accidentally breaks Phase 1

→ system still builds, but business lifecycle drifts

\`\`\`

**### Mandatory rule**

**\*\*No future patch may change a canonical flow, ownership boundary, invariant, or KEEP-AS-IS item in this file unless that change is explicitly reviewed and this baseline is updated first.\*\***

This file is not a changelog.  

It is the system contract that every phase must preserve.

\---

**# 2. System ownership**

**## 2.1 \`meeting-frontend\`**

Responsibilities:

\- UI / UX.

\- Management screens.

\- Meeting room/prejoin.

\- Call Java APIs.

\- Render backend-derived business state.

\- Never become source of truth for meeting/minutes lifecycle.

Must NOT:

\- invent business state independently.

\- directly own Minutes lifecycle.

\- bypass Java to control Capture/STT.

\- decide permissions independently from backend contract.

\---

**## 2.2 \`meeting-service\` — Java**

**\*\*Durable business source of truth.\*\***

Responsibilities:

\- Authentication / authorization.

\- Meeting lifecycle.

\- Participants.

\- Capture session durable state.

\- Minutes job durable state.

\- STT reconciliation.

\- Transcript persistence.

\- VOICE + CHAT timeline.

\- Minutes generation and versioning.

\- Retry/reset rules.

\- PostgreSQL persistence.

Must remain the canonical owner of business state.

\---

**## 2.3 \`meeting-fastapi\`**

Responsibilities:

\- LiveKit audio capture runtime.

\- WAV + manifest.

\- Async STT execution.

\- Chunking.

\- Bounded STT concurrency.

\- Per-chunk retry.

\- Runtime STT status.

\- Progress callback.

\- Completion callback.

\- Result API.

Current limitation after Phase 5 / P1:

\- STT job identity, status, progress, and terminal result have durable local restart checkpoints under `CAPTURE_ROOT_DIR/_stt_jobs`.

\- Runtime tasks remain process-local.

\- Capture runtime state remains process-local.

\- FastAPI is not the durable business source of truth.

Until shared persistent runtime ownership exists:

\- Treat FastAPI STT as a single-active-instance runtime with local restart durability only.

\- Treat FastAPI Capture as single-instance runtime.

\- Do not assume horizontal scaling is safe.

\---

**# 3. Canonical interaction map**

\`\`\`text

meeting-frontend

      |

      v

meeting-service

  Java + PostgreSQL

      |

      +---------------------------+

      |                           |

      | Capture                   | Minutes/STT

      v                           v

meeting-fastapi              meeting-fastapi

LiveKit Capture              Async STT

      |                           |

      v                           |

WAV + manifest                    |

                                  |

                     progress callback → Java

                     completion callback → Java

                                  |

                                  v

                        BUILDING\_TRANSCRIPT

                                  |

                        Java GET /result

                                  |

                        persist transcript

                                  |

                        VOICE + CHAT timeline

                                  |

                        GENERATING\_MINUTES

                                  |

                        Minutes V1 DRAFT

\`\`\`

There must be **\*\*one canonical path\*\***, not parallel orchestration paths.

\---

**# 4. Canonical Minutes state machine**

\`\`\`text

Generate

  |

  v

QUEUED / VALIDATING

  |

  v

PROCESSING / PREPARING\_AUDIO

  |

  v

PROCESSING / TRANSCRIBING

  |

  | Python progress callbacks

  |

  | Python completion callback

  | OR stale reconciliation fallback

  v

PROCESSING / BUILDING\_TRANSCRIPT

transcriptStatus = PENDING

  |

  v

transcriptStatus = PROCESSING

  |

  v

GET Python /result

  |

  v

transcriptStatus = READY

PROCESSING / GENERATING\_MINUTES

  |

  v

COMPLETED / DRAFT\_READY

\`\`\`

Terminal failure:

\`\`\`text

Any definitive unrecoverable step

→ status = FAILED

→ stage = FAILED

\`\`\`

Manual user/admin actions then decide:

\- Retry

\- Reset derived Minutes data

There is no automatic **whole Minutes-job retry**.

Automatic recovery is allowed only where explicitly frozen by this baseline:

\- stale \`PREPARING_AUDIO\` may replay the **same logical STT submission** with the same deterministic requestId.

\- stale transcript \`PROCESSING\` may return to the existing \`PENDING\` persistence path or fail at the existing attempt limit.

\- stale/recovery logic must restore the same canonical pipeline, not create a second pipeline.

\---

**# 5. STT / transcript recovery contract — FROZEN after Phase 3**

The normal success path remains callback-driven.

Phase 3 adds recovery only for crash-orphaned work while preserving the same canonical state machine.

**## 5.1 TRANSCRIBING reconciliation — Phase 2 FROZEN**

Recovery runs for stale:

\`\`\`text

PROCESSING

\+

TRANSCRIBING

\+

processingId != null

\`\`\`

Recovery behavior:

\`\`\`text

Python 404

→ Java FAILED

Python QUEUED / PROCESSING

→ keep business state unchanged

→ refresh observation timestamp only

Python COMPLETED / PARTIAL

→ GET existing result

→ reuse existing applySttCompletion()

→ return to canonical BUILDING_TRANSCRIPT flow

Python FAILED

→ Java FAILED atomically

Network / timeout / 401 / 403 / 5xx

→ do NOT false-fail

→ leave Java state unchanged

\`\`\`

Mandatory invariants:

\- Recovery must never create a second STT job.

\- Recovery must never call \`generate()\`.

\- Recovery must never call \`retry()\`.

\- Recovery must never persist transcript itself.

\- Recovery must never generate Minutes itself.

\- Recovery must reuse canonical transitions.

\- Callback and recovery must converge to the same state.

\- Lock + state + processingId recheck protects callback/recovery races.

Definitive watchdog STT failure persists:

\`\`\`text

Minutes = FAILED

STT = FAILED

Transcript = FAILED

\`\`\`

while preserving forensic fields such as:

\- progress

\- processingId

\- attempt

\- timestamps

\- STT summary values

**## 5.2 PREPARING_AUDIO submission idempotency — I3 FROZEN**

Canonical logical submission identity:

\`\`\`text

requestId = "minutes-job-" + MeetingMinutesJobEntity.id

\`\`\`

Rules:

\- Java derives requestId deterministically from the durable Minutes job id.

\- requestId is not a new database column.

\- the canonical endpoint remains \`POST /api/postprocessing/stt/jobs\`.

\- first FastAPI requestId creates one processingId and one background STT task.

\- replay with the same requestId + same meetingId + same canonical manifestPaths returns the **same processingId** and must not create another task.

\- reuse of the same requestId with a different meetingId or manifestPaths is a contract conflict and returns HTTP \`409\`.

\- FastAPI requestId correlation remains in-memory under the current single-instance runtime limitation.

\- FastAPI restart may lose the in-memory idempotency map and in-memory STT job; Phase 3 does not add durable Python job storage.

Stale Java submission recovery runs only for:

\`\`\`text

status = PROCESSING

stage = PREPARING_AUDIO

sttProcessingId = null

updatedAt <= stale cutoff

\`\`\`

Current default stale cutoff:

\`\`\`text

minutes.processing.stt-submission-stale-after-seconds = 300

\`\`\`

Recovery flow:

\`\`\`text

find one oldest stale candidate

→ findByIdForUpdate(jobId)

→ recheck status/stage/processingId/cutoff

→ rebuild existing manifest input

→ refresh durable lease timestamp only

→ POST the SAME deterministic requestId

→ FastAPI returns existing/new processingId for that logical request

→ markTranscribing() only if job is still:

   PROCESSING
   PREPARING_AUDIO
   sttProcessingId = null

→ transition to TRANSCRIBING

\`\`\`

Mandatory invariants:

\- recovery must not create a new whole Minutes job.

\- recovery must not generate a new random Java request identity.

\- recovery must not overwrite an existing sttProcessingId.

\- \`markTranscribing()\` must not resurrect FAILED/COMPLETED/other-stage work.

\- a failure in the new PREPARING_AUDIO recovery path must not skip the already-frozen TRANSCRIBING recovery path in that scheduler tick.

**## 5.3 Transcript PROCESSING crash recovery — I1 FROZEN**

Recovery runs only for stale:

\`\`\`text

status = PROCESSING

stage = BUILDING_TRANSCRIPT

transcriptStatus = PROCESSING

updatedAt <= stale cutoff

\`\`\`

Current default stale cutoff:

\`\`\`text

minutes.processing.transcript-stale-after-seconds = 300

\`\`\`

Recovery flow:

\`\`\`text

find one oldest stale transcript candidate

→ findByIdForUpdate(jobId)

→ recheck status/stage/transcriptStatus/cutoff

→ if transcriptAttempts < maxAttempts:

     transcriptStatus = PENDING

     keep status = PROCESSING

     keep stage = BUILDING_TRANSCRIPT

     keep sttProcessingId unchanged

     do NOT increment transcriptAttempts

→ existing dispatcher selects PENDING

→ existing transcript persistence fetches the SAME Python result

\`\`\`

At the existing attempt limit:

\`\`\`text

transcriptStatus = FAILED

status = FAILED

stage = FAILED

finishedAt = now

\`\`\`

Mandatory invariants:

\- stale recovery does not POST a new STT job.

\- stale recovery does not create or replace processingId.

\- stale recovery does not increment transcriptAttempts; claim remains the owner of attempt increment.

\- stale recovery does not persist transcript itself.

\- stale recovery does not generate Minutes.

\- lock + state + cutoff recheck prevents overwriting newer state.

**## 5.4 Scheduled processing isolation — I4 FROZEN**

Current scheduling capacity:

\`\`\`text

spring.task.scheduling.pool.size = ${SCHEDULER_POOL_SIZE:5}

\`\`\`

Rules:

\- default scheduler pool size is 5.

\- deployment may override with environment variable \`SCHEDULER_POOL_SIZE\`.

\- the pool provides bounded scheduling concurrency so one blocking scheduled FastAPI call does not monopolize the only scheduler thread.

\- existing \`@Scheduled\` methods, fixed-delay values, one-job-per-tick semantics, transaction boundaries, and HTTP timeouts remain unchanged.

\- Phase 3 does not introduce \`@Async\`, custom executor orchestration, Kafka, Redis, or a second scheduler pipeline.

\---

**# 6. Capture contract**

Canonical Capture ownership:

\`\`\`text

Java MeetingCaptureService

→ durable meeting\_capture\_sessions

→ FastAPI CaptureManager

→ WAV + manifest

\`\`\`

Rules:

\- Java owns durable capture lifecycle.

\- FastAPI owns runtime capture execution.

\- Original WAV must not be deleted by STT.

\- Manifest paths are inputs to Minutes generation.

\- Multiple completed capture sessions may feed one meeting-level STT job.

\- One meeting-level STT request must create one Python processingId.

A legacy/parallel AI orchestration path must not remain as an alternative Capture owner.

**## 6.1 Participant connection lifecycle contract — FROZEN after Phase 1**

Canonical join/connection lifecycle:

\`\`\`text

POST Java /join

→ lock MeetingEntity with PESSIMISTIC\_WRITE

→ resolve participant under the lock

→ issue Java-owned LiveKit token

→ persist connection = PENDING

→ joinedAt = null

verified LiveKit participant\_joined

→ lock the same MeetingEntity row first

→ load connection by liveKitIdentity after the lock

→ connection = CONNECTED

→ joinedAt = actual join time

→ participant = JOINED

participant\_left / participant\_connection\_aborted

→ same MeetingEntity aggregate lock

→ current connection = LEFT

→ participant = LEFT only when no other connection remains CONNECTED

participant removal

→ same MeetingEntity aggregate lock

→ participant = REMOVED

→ all known participant connections = REMOVED

→ old issued token/webhook must not resurrect participant or connection

\`\`\`

Frozen concurrency rules:

\- \`MeetingEntity\` is the single aggregate serialization lock for participant lifecycle writes.

\- \`/join\`, participant add/update/role/delete, and verified participant lifecycle webhooks acquire the meeting row lock before participant/connection lifecycle reads or writes.

\- Webhook room lookup uses \`findByRoomNameForUpdate(...)\` before \`findByLiveKitIdentity(...)\`.

\- Do not add secondary participant/connection locks, \`@Version\`, Java \`synchronized\`, retry loops, or a second locking scheme unless explicitly reviewed.

\- Chat remains gated by \`ParticipantConnectionStatus.CONNECTED\`; \`PENDING\` is not an active chat connection.

\- \`REMOVED\` is terminal for participant/connection re-entry in the current lifecycle.

\- Multi-device participant presence is derived from whether at least one connection remains \`CONNECTED\`.

**## 6.2 Meeting ↔ Capture terminal lifecycle — CLOSED after C5/C6**

Terminal join:

\`\`\`text

COMPLETED

CANCELLED

time-derived COMPLETED

→ cannot enter/re-enter realtime join lifecycle

→ no LiveKit room/token work is allowed after terminal rejection

\`\`\`

Canonical explicit end:

\`\`\`text

POST /api/meetings/{meetingId}/end

→ MeetingEntity PESSIMISTIC\_WRITE

→ existing end authorization

→ finalize Capture consistency

→ only after Capture finalization succeeds:

     persist MeetingStatus.COMPLETED

→ best-effort LiveKit room deletion

\`\`\`

Mandatory invariants:

\- \`endMeeting()\` is the canonical durable transition to \`COMPLETED\`.

\- generic \`updateMeeting()\` must not create \`COMPLETED\`.

\- generic update must not reopen persisted \`COMPLETED\`.

\- clock-derived effective \`COMPLETED\` must not be automatically persisted by generic update.

\- \`createMeeting()\` durably persists \`UPCOMING\`; effective/display status may be returned separately without mutating the managed entity.

\- \`CANCELLED\` must not be converted to \`COMPLETED\` by \`endMeeting()\`.

Minutes generation readiness:

\`\`\`text

meeting persisted status == COMPLETED

AND

no Capture is STARTING / RUNNING / STOPPING

AND

newest Capture lifecycle attempt is not unresolved FAILED

AND

newest COMPLETED attempt has valid manifest

AND

at least one valid COMPLETED manifest exists

\`\`\`

Additional Minutes readiness rules:

\- scheduled \`endTime\` alone is not enough.

\- newest Capture lifecycle attempt is determined by durable capture session identity/order, not by nullable \`startedAt\` list tail.

\- valid COMPLETED manifests are still returned chronologically.

\- multiple valid manifests still feed one meeting-level STT request.

**## 6.3 Capture restart recovery — CLOSED after C8**

Final canonical correlation:

\`\`\`text

Java meeting\_capture\_sessions.id

        ↕ captureSessionId

FastAPI CaptureSession

        ↕

FastAPI manifest

\`\`\`

Rules:

\- Java capture row ID is the cross-service \`captureSessionId\`.

\- FastAPI runtime CaptureSession stores \`captureSessionId\`.

\- manifest root stores \`captureSessionId\`.

\- correlated status recovery uses exact runtime and exact manifest evidence.

\- recovery must never use latest/mtime-newest unrelated manifest as evidence.

Exact recovery evidence order:

\`\`\`text

Java durable capture row

\+

exact FastAPI runtime

\+

exact FastAPI manifest

\`\`\`

Graceful restart:

\`\`\`text

Java RUNNING / STARTING / STOPPING

\+

runtime absent

\+

exact manifest COMPLETED

→ Java Capture COMPLETED

\`\`\`

Hard runtime loss:

\`\`\`text

runtime absent

\+

exact manifest RUNNING

→ Capture FAILED

\`\`\`

An incomplete manifest must not be claimed as completed.

Exact \`NOT\_FOUND\` is definitive only after correlated FastAPI status confirms:

\`\`\`text

no exact runtime

AND

no exact manifest

\`\`\`

Transport uncertainty:

\`\`\`text

timeout

network failure

401

403

5xx

invalid transport result

\`\`\`

must not automatically become business \`FAILED\`. Durable \`STARTING\`, \`RUNNING\`, or \`STOPPING\` is preserved and reconciliation retries later.

FastAPI in-flight start visibility:

\`\`\`text

FastAPI CaptureSession is published into CaptureManager

BEFORE awaiting asynchronous session.start().

\`\`\`

Therefore during a real in-flight start:

\`\`\`text

runtimePresent = true

status = STARTING

\`\`\`

Java recovery must:

\`\`\`text

KEEP STARTING

→ no FAILED

→ no stop

→ no replacement start

→ observe again later

\`\`\`

This distinguishes an active slow start from hard runtime loss.

Mandatory recovery ownership invariants:

\- Recovery is reconciliation only.

\- Recovery must not call Capture start to create a replacement session.

\- Recovery must not complete Meeting status.

\- Recovery must not call \`endMeeting()\`.

\- Recovery must not generate Minutes.

\- Recovery must not start STT.

\- Recovery must not persist transcript.

\- Recovery remote status/stop I/O occurs outside long DB transactions.

\- Recovery state claims remain serialized through \`MeetingEntity PESSIMISTIC\_WRITE\`.

\- stale claim/recheck prevents duplicate multi-instance recovery work.

\- no Redis/JVM/advisory secondary lock is introduced.

\---

**# 7. Minutes generation/versioning contract**

\- Java persists transcript.

\- Java builds VOICE + CHAT timeline.

\- Java generates Minutes.

\- Initial generated version is immutable system output.

\- Editing creates a new DRAFT version.

\- FINAL version is not directly overwritten.

\- Reset Minutes deletes derived Minutes/STT data only.

\- Reset must preserve capture WAV/manifest and meeting data.

No future phase may bypass version creation to mutate generated/final historical data directly.

**## 7.1 Initial Minutes generation failure / starvation — I2 FROZEN**

Eligible generation work remains:

\`\`\`text

status = PROCESSING

stage = GENERATING_MINUTES

transcriptStatus = READY

\`\`\`

If \`createInitialVersion(jobId)\` throws:

\`\`\`text

generation transaction rolls back

→ dispatcher receives the exception

→ separate transactional markGenerationFailure(jobId, message)

→ findByIdForUpdate(jobId)

→ recheck PROCESSING / GENERATING_MINUTES / READY

→ status = FAILED

→ stage = FAILED

→ finishedAt = now

\`\`\`

Rules:

\- failed generation must not remain the oldest eligible job forever.

\- the next scheduler tick may select the next eligible Minutes job.

\- transcriptStatus remains \`READY\`.

\- sttProcessingId, transcript attempts, STT fields, meeting data, and persisted transcript rows are preserved.

\- failure marking must not create a new Minutes processing attempt.

\- failure marking must not overwrite a job that changed to a newer successful/terminal state before the lock/recheck.

\- the dispatcher keeps its existing outer exception boundary so repository lookup failure does not escape solely because of the I2 patch.

\---

**# 8. Global invariants**

These invariants apply to every phase.

**## I-01 — One source of truth**

Java/PostgreSQL is the durable business source of truth.

**## I-02 — One canonical pipeline**

No duplicate Capture, STT, Transcript, or Minutes orchestration path.

**## I-03 — No hanging non-terminal state**

Every non-terminal state must have:

\- a normal forward path, or

\- a defined recovery path, or

\- a safe transition to FAILED.

**## I-04 — No false failure on uncertainty**

Temporary network/5xx/timeout cannot automatically become business FAILED.

**## I-05 — No duplicate work**

A fix must not accidentally create:

\- duplicate Python STT jobs

\- duplicate transcript persistence

\- duplicate Minutes versions

\- duplicate Capture sessions

**## I-06 — Recovery is reconciliation**

Recovery restores the canonical flow; it must not become a second pipeline.

**## I-07 — Permissions are backend-owned**

FE may hide/show UI, but backend authorization must remain authoritative.

**## I-08 — Terminal state consistency**

Equivalent failure paths must converge to equivalent DB terminal state.

**## I-09 — Historical/forensic data is preserved**

Failure recovery must not erase useful processing IDs, progress, attempts, timestamps, or error context unless the explicit operation is Reset.

**## I-10 — No unnecessary schema/state expansion**

Do not add enum/status/stage/column/API solely to patch an edge case when the existing state machine can represent it.

**## I-11 — Idempotent remote submission**

When Java may replay an uncertain STT submission, the replay must identify the same logical work and must not create duplicate live STT work for an identical request.

**## I-12 — Recovery path isolation**

A newly-added recovery path must not suppress an already-frozen adjacent recovery path merely because the new path fails.

\---

**# 9. KEEP AS-IS baseline**

The following areas are currently considered correct and must not be changed incidentally.

\- STT typed status reconciliation.

\- Definitive Python 404 → atomic Java FAILED.

\- Python COMPLETED/PARTIAL recovery → reuse \`applySttCompletion()\`.

\- Network/5xx/timeout → no false FAILED.

\- Progress remains callback-driven.

\- Completion callback remains primary success path.

\- Progress cannot move backward.

\- One active Minutes job per meeting.

\- One active Capture session per meeting.

\- Multi-manifest → one meeting-level processingId.

\- Bounded STT concurrency.

\- Per-chunk retry.

\- Original WAV preservation.

\- Java transcript result validation.

\- Generated Minutes versioning model.

\- FINAL historical version not directly mutated.

\- Reset Minutes preserves Capture artifacts.

\- Existing admin Minutes recovery/reset semantics unless a phase explicitly targets them.

\- Phase 1 canonical join route: \`/join/{meetingId}\` → Java \`join-info\` → Java \`/join\` → Java-issued LiveKit token.

\- No frontend/server-side direct LiveKit token minting, room-management bypass, or Egress-control bypass.

\- Public \`join-info\` is separated from full management meeting detail; PRIVATE join-info does not expose the meeting title.

\- Full meeting detail remains authenticated and backend-authorized.

\- Participant management mutations remain protected by meeting-level backend authorization.

\- Official LiveKit webhook verification remains \`WebhookReceiver.receive(rawBody, Authorization)\`.

\- Participant connections are created \`PENDING\` by \`/join\` and become \`CONNECTED\` only through verified \`participant\_joined\`.

\- Participant lifecycle writes remain serialized by the single \`MeetingEntity\` \`PESSIMISTIC\_WRITE\` aggregate lock.

\- \`REMOVED\` participant/connection state must not be resurrected by an old issued token or webhook.

\- Multi-device LEFT semantics remain: participant becomes LEFT only when no connection remains CONNECTED.

\- C5 terminal join rejection must occur before participant/realtime/LiveKit work.

\- \`endMeeting()\` remains lock-first and Capture-before-COMPLETED.

\- generic meeting update cannot bypass canonical \`COMPLETED\`.

\- createMeeting must not dirty-persist effective clock-derived \`COMPLETED\`.

\- Minutes readiness requires persisted explicit Meeting \`COMPLETED\`.

\- active/newer unresolved Capture blocks Minutes.

\- multiple valid completed Capture manifests remain supported.

\- Capture start/stop durable claims remain serialized through the \`MeetingEntity\` aggregate lock.

\- \`captureSessionId\` remains the exact Java ↔ FastAPI ↔ manifest correlation key.

\- correlated recovery must not use unrelated/latest manifest fallback.

\- uncertain network outcome must not false-fail Capture.

\- recovery must not auto-start Capture.

\- recovery must not auto-complete Meeting.

\- live FastAPI \`STARTING\` runtime must remain observable while asynchronous start is in flight.

\- canonical End Meeting permission remains \`ADMIN\`, \`MEETING\_MANAGER\`, meeting creator, or \`CHAIRMAN\`.

\- \`canManageMeeting\` remains \`ADMIN\`, meeting creator, or \`CHAIRMAN\`; \`MEETING\_MANAGER\` is not added to participant-management authorization.

\- \`POST /api/meetings/{id}/end\` remains authenticated at Spring Security and business-authorized by \`requireCanEndMeeting(...)\`.

\- join response \`canEndMeeting\` and \`canManageParticipants\` remain independently derived.

\- frontend End Meeting visibility remains backend-driven from \`join.permissions.canEndMeeting\`.

\- stale transcript PROCESSING recovery remains lock/recheck based and returns below-max attempts to the existing PENDING persistence path without incrementing transcriptAttempts.

\- transcript recovery preserves the existing sttProcessingId and never starts a new STT job.

\- initial Minutes generation failure is removed from scheduler eligibility by the separate lock/recheck FAILED transition; one bad job must not starve later jobs.

\- deterministic STT submission identity remains \`requestId = "minutes-job-" + jobId\`.

\- identical FastAPI replay for the same requestId/meeting/manifests returns the same processingId and starts no duplicate task.

\- requestId payload conflict remains HTTP \`409\`.

\- stale PREPARING_AUDIO recovery replays the same deterministic requestId and does not add a new DB idempotency field.

\- \`markTranscribing()\` remains guarded by \`PROCESSING / PREPARING_AUDIO / sttProcessingId=null\`.

\- PREPARING_AUDIO recovery failure must not skip existing TRANSCRIBING recovery.

\- scheduler isolation remains the Spring Boot bounded scheduling pool with default size 5 and environment override \`SCHEDULER_POOL_SIZE\`.

\- I4 must not be silently replaced with a parallel \`@Async\`/custom-executor orchestration path.

\- Phase 3 did not change frontend UI, PUBLIC Guest join, PRIVATE auth/join, Capture permissions, End Meeting permissions, or Phase-1/2 canonical flows.

\- meeting list effective-status filtering and response mapping remain semantically aligned without dirty-writing persisted lifecycle status.

\- real transcript UI remains Java/PostgreSQL-backed; no production mock transcript fallback.

\- latest Minutes job governs transcript visibility; no older READY transcript fallback when latest is not READY.

\- persisted participant update/delete uses participantKey; no userId mutation locator fallback.

\- frontend Meeting business lifecycle remains Java/PostgreSQL-backed; no localStorage/mock alternate lifecycle.

\- Capture remains manual from ManagementCaptureControl; no automatic frontend Capture start.

\- frontend must not reintroduce direct recording/LiveKit infrastructure ownership removed during Phase 4 cleanup.

\- Java internal STT callbacks fail closed when configured internal key is blank.

\- Missing, blank, or wrong callback internal key is rejected with HTTP \`401\`.

\- Exact matching internal key continues into existing callback business logic.

\- Java ↔ FastAPI internal authentication continues to use the existing \`X-Internal-Api-Key\` shared-secret contract.

\- \`/internal/api/**\` remains outside browser-session authentication; internal callbacks are authenticated by the service key.

\- FastAPI \`/health\` remains public.

\- FastAPI Capture/STT auth continues to use \`require_internal_api_key\`.

\- P2 must not be replaced incidentally by JWT/OAuth/session auth without explicit baseline review.

\- accepted FastAPI STT request identity must be durable before successful acknowledgement.

\- identical requestId/identity replay across FastAPI restart returns the same processingId.

\- conflicting requestId reuse remains HTTP `409`.

\- terminal STT result must be durable before terminal callback/visibility.

\- non-terminal FastAPI STT work resumes after single-instance restart using the same processingId.

\- progress and processedChunks must not regress after restart.

\- shutdown-owned cancellation must not produce a false FAILED STT job.

\- terminal rehydration must not rerun STT or automatically resend completion solely because of startup.

\- corrupt durable STT records fail startup closed instead of creating false 404/idempotency loss.

\- Java definitive 404 semantics remain unchanged.

\- Java/PostgreSQL remains durable business source of truth.

\- P1 does not imply horizontal FastAPI/Capture scaling safety.

\- current STT timing remains chunk-level unless real chronology evidence justifies reopening P3.

\- participant identity remains track/manifest-derived; do not add diarization incidentally.

\- STT_CHUNK_SECONDS must not be reduced merely for theoretical timestamp precision without cost/quality review.

\- one-chunk/one-result semantics must not be changed without reviewing Java transcript validation and persistence.

\- no utterance-level provider response format should be introduced incidentally.

\- current frontend transcript rendering remains unchanged.


If a future task needs to touch one of these items, STOP and review the baseline before coding.

If a future phase needs to change any C5/C6/C8 invariant above, STOP and explicitly review the baseline first.

\---

**# 10. Audited issue registry**

**## PHASE 1 — SECURITY + CANONICAL OWNERSHIP**

**### C1 — Legacy AI flow — CLOSED / FROZEN**

Risk:

\- parallel Capture orchestration outside canonical \`MeetingCaptureService\`.

Frozen result:

\- legacy Java AI orchestration is removed from the production runtime path.

\- FastAPI legacy \`/api/ai/...\` Capture aliases are removed.

\- canonical Capture ownership remains \`MeetingCaptureService\` → FastAPI \`/api/capture/...\`.

**### C2 — Participant authorization — CLOSED / FROZEN**

Risk:

\- participant mutation/read endpoints were not consistently protected by meeting-level permission rules.

Frozen result:

\- participant read/mutation services enforce backend meeting-level authorization.

\- current \`canManageMeeting\` semantics remain \`ADMIN\`, meeting creator, or \`CHAIRMAN\`.

\- broader end-meeting permission alignment remains Phase 2 / I6.

**### C3 — Direct LiveKit API exposure — CLOSED / FROZEN**

Risk:

\- infrastructure room APIs could bypass meeting lifecycle.

Frozen result:

\- direct Java public room-management endpoint is removed.

\- frontend direct token minting / \`RoomServiceClient\` / Egress control paths are removed.

\- browser join uses \`/join/{meetingId}\` and Java-issued LiveKit credentials.

**### C4 — Public Meeting DTO/privacy — CLOSED / FROZEN**

Risk:

\- public GET paths could expose management/private participant data.

Frozen result:

\- public \`GET /api/meetings/{id}/join-info\` exposes only safe join metadata.

\- PRIVATE join-info returns \`title = null\`.

\- full \`GET /api/meetings/{id}\` remains authenticated and uses backend view authorization.

\- global meeting list remains restricted to \`ADMIN\` / \`MEETING\_MANAGER\`.

**### C7 — Webhook/connection lifecycle — CLOSED / FROZEN**

Risk:

\- webhook auth/lifecycle/identity handling and connection LEFT state were not reliably aligned.

Frozen result:

\- LiveKit webhook is cryptographically verified before DB mutation.

\- identity mapping uses exact \`liveKitIdentity\`.

\- \`/join\` persists connection as \`PENDING\`; verified \`participant\_joined\` is the canonical \`CONNECTED\` transition.

\- \`participant\_left\` / \`participant\_connection\_aborted\` use multi-device-aware LEFT semantics.

\- participant removal marks known connections \`REMOVED\`; old-token join events cannot resurrect them.

\- participant lifecycle writes are serialized by the \`MeetingEntity\` \`PESSIMISTIC\_WRITE\` aggregate lock.

\---

**## PHASE 2 — MEETING + CAPTURE CONSISTENCY**

**### C5 — Terminal meeting join — CLOSED**

Risk:

\- COMPLETED/CANCELLED meeting may recreate a LiveKit room.

Frozen/closed result:

\- \`/join\` acquires the \`MeetingEntity\` aggregate lock first.

\- effective meeting status is resolved before participant/realtime work.

\- \`COMPLETED\`, \`CANCELLED\`, and time-derived \`COMPLETED\` are rejected.

\- rejected terminal join performs no room recreation or token issuance.

**### C6 — End Meeting ↔ Capture ↔ Minutes — CLOSED**

Risk:

\- Meeting can complete while Capture remains running/stale.

\- Minutes may use old completed capture while newer capture is still active.

Frozen/closed result:

\- \`endMeeting()\` uses the Meeting aggregate lock.

\- Capture consistency is finalized before newly persisting Meeting \`COMPLETED\`.

\- uncertain terminal Capture finalization prevents false Meeting completion.

\- generic update cannot create/reopen \`COMPLETED\`.

\- create flow does not dirty-persist clock-derived \`COMPLETED\`.

\- Minutes readiness requires persisted explicit Meeting completion.

\- active/newer unresolved Capture blocks old-manifest fallback.

\- multiple valid completed manifests remain supported.

**### C8 — Capture restart recovery — CLOSED**

Risk:

\- FastAPI restart loses runtime Capture state while Java remains RUNNING.

Frozen/closed result:

\- Java capture row id is propagated as \`captureSessionId\`.

\- FastAPI runtime and manifest persist the same correlation.

\- status recovery checks exact runtime/manifest evidence.

\- graceful restart can recover exact COMPLETED manifest.

\- hard runtime loss with unfinalized RUNNING manifest does not false-complete.

\- uncertain network outcomes remain unresolved instead of immediately FAILED.

\- stale recovery uses aggregate-lock claim/recheck semantics.

\- recovery never auto-starts replacement Capture.

\- recovery never completes Meeting.

\- an in-flight FastAPI STARTING session is published to runtime before asynchronous start completes.

**### I6 — End permission contract — CLOSED / FROZEN**

Risk:

\- \`canEndMeeting\`, backend security, and FE behavior can disagree.

Frozen result:

\- canonical End Meeting permission is \`ADMIN\`, \`MEETING\_MANAGER\`, meeting creator, or \`CHAIRMAN\`.

\- \`SECRETARY\`, \`MEMBER\`, \`GUEST\`, anonymous, and unauthorized users remain denied.

\- Spring Security requires authentication for \`POST /api/meetings/{id}/end\`; final business authorization is owned by \`MeetingAccessPolicyService.requireCanEndMeeting(...)\`.

\- \`canManageMeeting\` remains unchanged as \`ADMIN\`, meeting creator, or \`CHAIRMAN\`.

\- \`MEETING\_MANAGER\` may end a meeting without implicitly gaining participant-management permission.

\- join permissions independently expose \`canEndMeeting\` and \`canManageParticipants\`.

\- frontend End Meeting visibility derives only from backend \`join.permissions.canEndMeeting\`.

\- frontend reuses the existing \`endManagementMeeting(meetingId)\` API and does not reconstruct role logic independently.

Status:

\- PASS / CLOSED / FROZEN

\---

**## PHASE 3 — MINUTES CRASH RECOVERY — CLOSED / FROZEN**

**### I1 — Transcript PROCESSING crash recovery — CLOSED / FROZEN**

Risk:

\- Java restart after transcript claim may leave \`transcriptStatus=PROCESSING\` forever.

Frozen result:

\- stale \`PROCESSING / BUILDING_TRANSCRIPT / transcriptStatus=PROCESSING\` is selected by age and rechecked under row lock.

\- default stale threshold is 300 seconds.

\- below max attempts, recovery returns only \`transcriptStatus\` to \`PENDING\`.

\- recovery does not increment transcriptAttempts.

\- recovery preserves sttProcessingId and reuses the existing transcript persistence path.

\- at the existing attempt limit, transcript/job/stage converge to \`FAILED\`.

\- no STT resubmission or Minutes generation is performed by transcript recovery.

**### I2 — Minutes generation starvation — CLOSED / FROZEN**

Risk:

\- one deterministic generation failure may remain oldest forever and block later jobs.

Frozen result:

\- \`createInitialVersion()\` remains its own transaction.

\- if generation throws, that transaction rolls back first.

\- a separate transactional, lock/recheck failure transition moves an unchanged eligible job to \`FAILED / FAILED\`.

\- transcript \`READY\`, sttProcessingId, STT/transcript forensic data, meeting data, and transcript rows are preserved.

\- the failed job leaves the eligible scheduler set, so a later job can be selected on the next tick.

\- the dispatcher's pre-existing outer exception boundary is preserved.

**### I3 — PREPARING_AUDIO idempotency — CLOSED / FROZEN**

Risk:

\- crash after Python accepts a job but before Java stores processingId can leave Java at \`PREPARING_AUDIO / sttProcessingId=null\`.

\- blind retry can create duplicate STT jobs.

Frozen result:

\- Java derives deterministic \`requestId = "minutes-job-" + jobId\`.

\- FastAPI correlates requestId to processingId in-memory.

\- identical replay returns the same processingId and does not create another background task.

\- same requestId with different meetingId or manifestPaths returns HTTP \`409\`.

\- stale PREPARING_AUDIO recovery uses a 300-second default cutoff, lock/recheck, and replays the same requestId.

\- \`markTranscribing()\` only transitions an unchanged \`PROCESSING / PREPARING_AUDIO / sttProcessingId=null\` job.

\- no schema, enum, migration, or new endpoint was introduced.

\- Phase 5 / P1 added durable local single-instance STT checkpoints; durable/shared multi-instance runtime ownership remains future reviewed work.

\- failure of PREPARING_AUDIO recovery is isolated so the existing TRANSCRIBING recovery still runs.

**### I4 — Scheduler isolation — CLOSED / FROZEN**

Risk:

\- synchronous Python network calls could monopolize the default single scheduler thread and delay unrelated scheduled workflows.

Frozen result:

\- \`spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:5}\`.

\- default scheduling pool size is 5.

\- deployment may override with \`SCHEDULER_POOL_SIZE\`.

\- existing five \`@Scheduled\` workflows, delays, selectors, one-job-per-tick semantics, transaction boundaries, and HTTP timeouts remain unchanged.

\- no \`@Async\`, custom executor, second scheduler pipeline, Redis, or Kafka was added.

Status:

\- PASS / CLOSED / FROZEN

\---

**## PHASE 4 — CONTRACT / UI CORRECTNESS**

**### I5 — Meeting status filtering — CLOSED / FROZEN**

Risk:

\- query could filter persisted status while response displayed effective clock-derived status.

Frozen result:

\- meeting list filtering and response use the same effective status semantics.

\- one \`LocalDateTime now\` is used per list request.

\- filtering occurs in the database Specification, not by filtering an already-paged result in memory.

\- terminal persisted CANCELLED remains CANCELLED.

\- terminal persisted COMPLETED remains COMPLETED.

\- non-terminal effective status rules remain:

\`\`\`text

startTime > now
→ UPCOMING

startTime <= now && endTime >= now
→ IN_PROGRESS

endTime < now
→ COMPLETED

\`\`\`

\- exact start/end boundary is IN_PROGRESS.

\- list read path does not dirty-write effective status into the managed entity.

\- persisted meeting lifecycle ownership remains unchanged.

\- no frontend or FastAPI behavior was changed by I5.

**### I7 — Real transcript UI — CLOSED / FROZEN**

Risk:

\- Minutes UI can display mock transcript while generation used real transcript.

Frozen result:

Canonical UI transcript path is:

\`\`\`text

PostgreSQL meeting_transcript_chunks
→ Java
  GET /api/meetings/{meetingId}/minutes/transcript
→ frontend managementMinutesService
→ MinutesDetailPage
→ TranscriptViewer

\`\`\`

Rules:

\- Java authorizes transcript access through the existing meeting Minutes authorization path.

\- transcript comes only from the latest Minutes job.

\- rows are returned only when the latest job transcriptStatus is READY.

\- no fallback to an older READY attempt when the latest attempt is not READY.

\- rows remain ordered by resultOrder ASC.

\- internal processing/file fields are not exposed in the UI DTO.

\- frontend no longer uses mock transcript data.

\- no frontend → FastAPI transcript request exists.

\- TranscriptViewer layout was not redesigned.

**### I8 — participantKey FE ↔ BE — CLOSED / FROZEN**

Risk:

\- FE and Java may use different participant identifiers for update/delete.

Frozen result:

\- persisted participant mutation locator is participantKey.

\- update uses:

\`\`\`text

PUT /api/meetings/{meetingId}/participants/{participantKey}

\`\`\`

\- delete uses:

\`\`\`text

DELETE /api/meetings/{meetingId}/participants/{participantKey}

\`\`\`

\- userId remains logical/person identity for frontend form/diff matching.

\- userId is not a mutation URL fallback.

\- missing participantKey for an existing persisted participant fails instead of silently falling back to userId.

\- add-participant POST remains unchanged.

\- Java/FastAPI were not modified by I8.

**### CLEAN-1 — legacy recording path — FROZEN**

Frozen result:

\- no automatic Capture start/stop runtime path exists.

\- \`ManagementCaptureControl\` remains the only production caller of \`startManagementCapture\`.

\- current manual flow remains:

\`\`\`text

user clicks "Bắt đầu ghi BB"
→ frontend
→ Java capture/start
→ FastAPI Capture

\`\`\`

\- stop remains manual through Java → FastAPI.

\- old frontend direct recording endpoint usage was removed.

\- \`NEXT_PUBLIC_LK_RECORD_ENDPOINT\` has no production reader.

\- old unused RecordingIndicator was removed.

\- no Capture UI redesign occurred.

**### CLEAN-2 — dead mock Meeting lifecycle — FROZEN**

Frozen result:

Removed isolated unused frontend mock/localStorage Meeting lifecycle:

\- \`services/mockMeetingService.ts\`

\- \`services/meetingService.ts\`

\- \`types/meeting.ts\`

\- \`data/mockMeetings.ts\`

Removed behavior:

\`\`\`text

BrowserMockMeetingService
→ localStorage
→ cusc-smart-meeting-store

\`\`\`

Canonical Meeting ownership remains:

\`\`\`text

frontend
→ Java meeting-service
→ PostgreSQL

\`\`\`

No mock fallback replaced it.

**### CLEAN-3 — production mock Minutes dependency — FROZEN**

Frozen result:

\- ManagementShell no longer reads mockManagementMinutes.

\- breadcrumb fallback is:

\`\`\`text

BREADCRUMB_LABELS[segment]
?? decodeURIComponent(segment)

\`\`\`

\- no additional API call was introduced merely for breadcrumb title.

\- obsolete mockManagementMinutes data/test were removed after zero-consumer proof.

\- real Minutes/transcript content remains Java-backed.

**### CLEAN-4A — dead LiveKit starter architecture — FROZEN**

After dependency audit, proven unused old starter/auth files were removed:

\- \`lib/Debug.tsx\`

\- \`lib/getLiveKitURL.ts\`

\- \`lib/getLiveKitURL.test.ts\`

\- \`lib/KeyboardShortcuts.tsx\`

\- \`lib/usePerfomanceOptimiser.ts\`

\- \`lib/useSetupE2EE.ts\`

\- \`components/auth/GoogleLoginButton.tsx\`

\- \`components/auth/GoogleOneTap.tsx\`

\- \`components/auth/InternalSsoButton.tsx\`

Rules:

\- only zero-consumer obsolete starter files were removed.

\- canonical Management auth remained untouched.

\- PUBLIC Guest join remained untouched.

\- PRIVATE auth/join remained untouched.

\- Java-issued LiveKit token flow remained untouched.

\- manual Capture remained untouched.

\- generic P3 harmless unused UI files were not treated as architecture blockers merely because they were unused.

**### CLEAN-4B — obsolete configuration documentation — FROZEN**

Frontend \`.env.example\` was aligned with current production readers.

Obsolete frontend example variables removed after zero-reader audit included old:

\- direct LiveKit server credentials

\- direct recording endpoint

\- S3 recording storage configuration

\- old settings-menu feature flag

\- old POC meeting-user variables

\- Datadog starter/debug variables

Frontend current documented key retained:

\`\`\`text

MEETING_SERVICE_INTERNAL_URL

\`\`\`

FastAPI \`.env.example\` obsolete:

\`\`\`text

CAPTURE_EMPTY_ROOM_GRACE_SECONDS

\`\`\`

was removed after proof that it had:

\- no Settings field

\- no getenv reader

\- no service consumer

Do NOT state that all FastAPI runtime settings are fully documented.

Carry forward this known Phase-5 deployment-hardening item:

The following live FastAPI settings were found to have production readers but were not yet fully represented in \`.env.example\` during CLEAN-4B:

\- \`MEETING_SERVICE_BASE_URL\`

\- \`STT_PROGRESS_CALLBACK_*\`

\- \`STT_COMPLETION_CALLBACK_*\`

They are NOT legacy and must not be deleted.

They remain Phase 5 / P2 deployment-hardening scope.

**### Phase 4 final integrity result**

\`\`\`text

P0 active parallel/wrong runtime flows: 0

P1 contract-hiding live business flows: 0

P2 dangerous dead legacy architecture: 0 after CLEAN-1 through CLEAN-4B

P3 harmless unused generic files: may remain and do NOT block Phase 4 freeze.

\`\`\`

Explicitly recorded:

\- no frontend → FastAPI direct business path.

\- no frontend LiveKit token minting.

\- no alternate Capture owner.

\- no mock/localStorage Meeting business lifecycle.

\- no mock transcript production path.

\- no userId mutation fallback replacing participantKey.

\- no automatic Capture path.

\- Java/PostgreSQL remains the durable business source of truth.

\- FastAPI remains Capture/STT runtime executor.

\- frontend remains UI/API consumer only.

**### Phase 4 validation record**

Final observed validation entering Phase 5:

\- Frontend: \`npx tsc --noEmit\` = PASS.

\- Frontend: \`npm test\` = PASS; 9 test files; 80 tests PASS.

\- Java: \`mvn test\` = PASS; 443 tests; 0 failures; 0 errors.

\- FastAPI: \`pytest\` = PASS; 126 tests PASS.

Environment/sandbox EPERM or pytest cache warnings are environment notes, not source-contract failures.

No destructive Java/FastAPI crash-restart runtime smoke was recorded as part of Phase 3 or Phase 4 freeze.

\---

**## PHASE 5 — PRODUCTION HARDENING**

**### P1 — FastAPI STT single-instance restart durability — CLOSED / FROZEN**

Risk:

\- FastAPI STT runtime state was process-memory only: `_jobs`, `_tasks`, `_request_processing_ids`, and `_request_identities`.

\- restart could lose accepted requestId â†’ processingId correlation, lose an already-persisted Java sttProcessingId, lose terminal results before Java fetched `/result`, lose progress high-water, or turn shutdown cancellation into a false FAILED state.

Frozen scope:

\- P1 provides durable STT job checkpoint/restart behavior for one active FastAPI runtime instance using the existing durable local filesystem.

\- Java/PostgreSQL remains the durable business source of truth.

\- FastAPI durable STT checkpointing is runtime execution persistence only.

\- P1 does not provide distributed execution ownership, horizontal FastAPI coordination, distributed locking, multi-instance Capture safety, Redis/shared cluster runtime state, or durable Capture runtime state.

Canonical relationship:

```text

Java MeetingMinutesJobEntity
        |
        | requestId = minutes-job-{jobId}
        |
        v
FastAPI STT job
        |
        +-- in-memory runtime
        |
        +-- atomic JSON checkpoint
             under:
             CAPTURE_ROOT_DIR / "_stt_jobs"

```

Durable job store:

\- one JSON record per processingId is stored under `CAPTURE_ROOT_DIR/_stt_jobs`.

\- writes are atomic: temporary file â†’ flush â†’ fsync â†’ `os.replace`.

\- no Redis, SQLite, PostgreSQL runtime table, Kafka, RabbitMQ, Celery, or new external dependency was added.

Persisted runtime checkpoint fields:

```text

schemaVersion
requestId
processingId
meetingId
manifestPaths
status
stage
processedChunks
totalChunks
progressPercent
error
result

```

Explicitly not persisted: `asyncio.Task`, HTTP clients, provider objects, LiveKit objects, CaptureSession, and Java business state.

Durable accept contract:

```text

check requestId / identity
â†’ generate processingId
â†’ construct QUEUED job
â†’ durably persist accepted identity/job
â†’ publish in-memory indexes
â†’ create one runtime task
â†’ return 202

```

\- FastAPI must not acknowledge a new logical STT job before `requestId + processingId + request identity` are durable.

\- if initial persistence fails, there is no successful `202`, no runtime task, and no half-accepted job.

Request-id restart contract:

\- same requestId + same meetingId + same validated manifestPaths, before or after restart, returns the same processingId and creates no second logical task/job.

\- same requestId + conflicting identity returns HTTP `409` and leaves the existing durable job unchanged.

\- Java request identity remains `requestId = "minutes-job-" + jobId`.

\- no Java DB idempotency column was introduced.

Startup rehydration:

\- FastAPI startup loads durable STT records before normal request serving.

\- startup rebuilds `_jobs`, `_request_processing_ids`, and `_request_identities`.

\- terminal `COMPLETED`, `PARTIAL`, and `FAILED` jobs remain queryable after restart and do not automatically rerun.

\- `COMPLETED` / `PARTIAL` result remains available through the existing `/result` endpoint.

\- non-terminal `QUEUED` / `PROCESSING` jobs recreate exactly one task in the current process using the same requestId, processingId, meetingId, and manifestPaths.

\- no per-chunk durable checkpointing was introduced; a resumed non-terminal job may re-run manifest transcription internally while preserving the same Java-visible processingId.

Duplicate task prevention:

\- normal submission and startup rehydration use the same service-level locking/task-ensuring model.

\- there is at most one live runtime task per processingId in the current process.

\- P1 does not introduce distributed locks.

Progress high-water:

\- persisted/API/callback progress must never move backward after restart.

\- high-water values are preserved for `progressPercent` and `processedChunks`.

\- runtime evidence observed `progress=50`, `processedChunks=1` before kill and `progress=100`, `processedChunks=2` after restart.

Terminal durability order:

```text

manifest transcription returns terminal result
â†’ persist full result + terminal state atomically
â†’ publish terminal state in memory
â†’ schedule completion callback

```

Therefore, when terminal completion becomes externally visible, the durable result already exists.

Graceful shutdown:

\- shutdown-owned cancellation does not convert active STT work into FAILED.

\- during normal FastAPI shutdown, runtime task cancellation preserves durable `QUEUED` / `PROCESSING` state.

\- no false FAILED completion callback is emitted solely because the process is shutting down.

\- next startup resumes the same processingId.

\- no `INTERRUPTED`, `RESTARTING`, `RECOVERING`, or other status was added.

Terminal startup callback rule:

\- rehydrating an already-terminal job does not automatically resend its completion callback merely because FastAPI restarted.

\- existing Java TRANSCRIBING reconciliation remains the recovery fallback for a callback lost during process restart.

\- a non-terminal job resumed after restart may send its normal completion callback when it later completes.

Corrupt store fail-closed:

\- durable record validation fails closed.

Supported status/stage pairs:

```text

QUEUED     â†” QUEUED
PROCESSING â†” TRANSCRIBING
COMPLETED  â†” COMPLETED
PARTIAL    â†” COMPLETED
FAILED     â†” FAILED

```

\- for every persisted non-null result, `result.meetingId == persisted meetingId` and `result.status == persisted job status`.

\- valid existing exception-failure representation remains `FAILED / FAILED / result=null`.

\- `COMPLETED` / `PARTIAL` require a matching result.

\- malformed, unsupported, or conflicting durable records prevent startup from serving an incomplete durable job index.

\- corrupt records must not be silently ignored in a way that creates false `404` or idempotency loss.

Retention:

\- P1 did not implement TTL, a retention scheduler, automatic terminal record deletion, or a delete-job API.

\- retention remains a separate operational decision because Java may require the durable result later after callback loss/restart.

Java production behavior:

\- Java production source changes for P1: 0.

\- existing Java Phase-3 recovery remains canonical.

\- PREPARING_AUDIO stale recovery still replays the same deterministic requestId.

\- TRANSCRIBING recovery still GETs the same processingId.

\- FastAPI HTTP `404` still means definitive Java FAILED.

\- network, timeout, HTTP `401`, HTTP `403`, and HTTP `5xx` still do not false-fail Java business state.

\- `COMPLETED` / `PARTIAL` still GET `/result` and reuse existing `applySttCompletion()`.

\- P1 fixed Python restart durability instead of weakening Java `404` semantics.

Capture behavior:

\- P1 did not persist or resume LiveKit runtime Capture state.

\- P1 did not modify CaptureManager, CaptureSession, LiveKit room state, captureSessionId correlation, Capture recovery, End Meeting, or manual Capture behavior.

\- C8 remains fully frozen.

P2/auth behavior:

\- P1 preserved `X-Internal-Api-Key`, `require_internal_api_key`, callback fail-closed behavior, SecurityConfig semantics, and the service-to-service secret contract.

\- no P1 auth redesign occurred.

Frontend and P3:

\- frontend P1 changes: 0.

\- no UI behavior changed.

\- P3 is NO CHANGE / DEFERRED; transcript timing remains chunk-level and utterance-level timing is not claimed.

\- transcript timing remains chunk-level; utterance-level timing is not claimed.

Automated validation:

\- FastAPI after core P1: 142 tests PASS.

\- FastAPI after fail-closed durable-store correction: 153 tests PASS.

\- Java full regression: 449 tests PASS.

\- Java recovery-focused: 109 tests PASS.

\- P1 source audit found no application source drift beyond approved FastAPI scope.

Runtime validation:

\- S1 request identity restart: accepted requestId â†’ processingId X â†’ hard FastAPI restart â†’ identical replay â†’ SAME X. PASS.

\- S2 conflict restart: same requestId + different identity after restart â†’ HTTP `409`. PASS.

\- S3 progress high-water: before restart `50%`, `processedChunks=1`; after restart `100%`, `processedChunks=2`. PASS.

\- S5 graceful shutdown: PROCESSING job â†’ graceful FastAPI shutdown â†’ durable state remained non-terminal â†’ no shutdown-induced FAILED â†’ restart returned SAME processingId. PASS.

\- terminal FAILED result survived hard restart with identical result SHA. PASS.

\- terminal COMPLETED processingId `bc2e8a98-bb01-468a-afac-f8d5952f5033` completed `2/2`; result SHA before restart `2e6f1f80f839a2db25e968246a71b8232978b6759debad869344b94011dfdbd4`; result SHA after restart same. PASS.

Final Java E2E destructive restart proof:

\- smoke meeting: `22`.

\- captureSessionId: `19`.

\- real input: 1 segment, 1 WAV file, 13,504,364 bytes.

\- Minutes job id: `9`.

\- requestId: `minutes-job-9`.

\- Java durable sttProcessingId: `0c475902-2317-44de-9de0-26e842ac569e`.

\- before kill: Java `PROCESSING / TRANSCRIBING`; FastAPI GET J HTTP 200, status `PROCESSING`, progress `0`, chunks `0/1`; FastAPI PID `11444`.

\- hard-kill FastAPI â†’ port 8000 unavailable â†’ start new FastAPI process PID `12564`.

\- after restart: GET same J HTTP 200 with SAME processingId; false 404: NO.

\- FastAPI eventually `COMPLETED`, `1/1` chunks, `100%`.

\- Java pipeline: `PROCESSING / TRANSCRIBING` â†’ `PROCESSING / BUILDING_TRANSCRIPT` â†’ `PROCESSING / GENERATING_MINUTES` â†’ `COMPLETED / DRAFT_READY`.

\- Java transcript `READY`, rows = 1.

\- Minutes Version 1 `DRAFT`.

\- no manual recovery invocation.

\- application source modifications during runtime smoke: 0.

\- P1 Java E2E: PASS.

Provider environment note:

\- initial smoke attempts failed provider connectivity because runtime `HTTP_PROXY`, `HTTPS_PROXY`, and `ALL_PROXY` pointed to `127.0.0.1:9`.

\- after removing those dead proxy values from the runtime process, provider connectivity succeeded.

\- this was an environment issue, not a P1 durability defect, and is not application source behavior.

**### P2 — Internal key deployment/authentication hardening — CLOSED / FROZEN**

Risk:

\- Java internal STT callback controllers failed open when \`ai-service.internal-api-key\` was blank.

\- deployment-level internal-key configuration needed to be explicit and complete.

Frozen result:

Java callback authentication now fails closed:

\`\`\`text

expected configured key blank
→ HTTP 401

incoming key missing
→ HTTP 401

incoming key blank
→ HTTP 401

incoming key wrong
→ HTTP 401

incoming key exact match
→ continue to existing callback state service

\`\`\`

This applies to both:

\- STT progress callback.

\- STT completion callback.

P2 preserved the existing shared header contract:

\`\`\`text

X-Internal-Api-Key

Java → FastAPI:

AI_INTERNAL_API_KEY
→ Java X-Internal-Api-Key header
→ FastAPI INTERNAL_API_KEY

FastAPI → Java:

FastAPI INTERNAL_API_KEY
→ X-Internal-Api-Key
→ Java ai-service.internal-api-key

\`\`\`

This is the existing service-to-service auth contract hardened by P2, not a new auth architecture.

SecurityConfig semantics remain KEEP-AS-IS:

\- \`/internal/api/**\` remains \`permitAll()\` at the Spring browser/session security layer.

\- STT internal callbacks remain excluded from browser CSRF requirement.

\- callback authentication remains owned by \`X-Internal-Api-Key\`.

\- FastAPI callbacks do not require JSESSIONID/browser authentication.

FastAPI auth semantics remain:

\- \`GET /health\` remains public.

\- Capture/STT routers use existing \`require_internal_api_key\`.

\- \`REQUIRE_INTERNAL_API_KEY=false\` keeps the explicit development bypass.

\- \`REQUIRE_INTERNAL_API_KEY=true\` with missing/wrong key returns HTTP \`401\`.

\- \`REQUIRE_INTERNAL_API_KEY=true\` with exact key allows normal route execution.

P2 did not change the FastAPI source default from \`false\` to \`true\`.

Production deployment requirement:

\`\`\`text

Java:
AI_INTERNAL_API_KEY=<same-shared-secret>

FastAPI:
INTERNAL_API_KEY=<strong-shared-secret>
REQUIRE_INTERNAL_API_KEY=true

\`\`\`

Production must not be considered protected if deployment leaves \`REQUIRE_INTERNAL_API_KEY=false\`.

Callback business semantics are unchanged:

\- \`applySttProgress(...)\` remains the progress callback business path.

\- \`applySttCompletion(...)\` remains the completion callback business path.

\- progress monotonic behavior is unchanged.

\- completion callback remains the primary success path.

\- callback/recovery convergence is unchanged.

\- callback HTTP \`401\` is authentication/configuration failure only and does not redefine the Python STT job itself as \`FAILED\`.

\- existing Java reconciliation remains fallback.

Java → FastAPI failure semantics are unchanged:

\- HTTP \`401\`, HTTP \`403\`, network, timeout, and HTTP \`5xx\` must not be reinterpreted by P2 as new immediate business failure semantics.

\- frozen Phase 2/3 uncertainty handling remains unchanged.

\- P2 does not modify Capture recovery, STT reconciliation, Minutes failure transitions, or processingId semantics.

Deployment configuration completeness:

\- \`meeting-fastapi/.env.example\` now documents current live callback/deployment settings:

\`\`\`text

MEETING_SERVICE_BASE_URL
STT_PROGRESS_CALLBACK_ENABLED
STT_PROGRESS_CALLBACK_TIMEOUT_SECONDS
STT_PROGRESS_CALLBACK_MIN_PERCENT_STEP
STT_COMPLETION_CALLBACK_ENABLED
STT_COMPLETION_CALLBACK_TIMEOUT_SECONDS
STT_COMPLETION_CALLBACK_MAX_ATTEMPTS
STT_COMPLETION_CALLBACK_RETRY_BASE_SECONDS

\`\`\`

\- \`INTERNAL_API_KEY\` must match Java \`AI_INTERNAL_API_KEY\`.

\- production must set \`REQUIRE_INTERNAL_API_KEY=true\`.

\- no real secret values are recorded.

\- obsolete \`CAPTURE_EMPTY_ROOM_GRACE_SECONDS\` remains removed.

Audited P2 source scope:

\- Java production modified: \`InternalSttProgressCallbackController.java\`, \`InternalSttCompletionCallbackController.java\`.

\- Java test created: \`InternalSttCallbackControllerTest.java\`.

\- FastAPI production source modified: none.

\- FastAPI test created: \`tests/test_internal_api_key_auth.py\`.

\- FastAPI configuration example modified: \`.env.example\`.

\- frontend modified: none.

P2 validation record:

\- Java focused: 35 tests PASS.

\- Java full: 449 tests PASS, 0 failures, 0 errors.

\- FastAPI focused: 19 tests PASS.

\- FastAPI full: 130 tests PASS.

\- frontend was not modified by P2.

\- pytest cache warning was environment-only, not a source-contract failure.

P2 source audit result:

\- Java \`SecurityConfig\`, \`SttClientImpl\`, \`CaptureClientImpl\`, and \`MeetingMinutesSttJobStateService\` were preserved.

\- FastAPI \`app/core/config.py\` runtime behavior, \`app/api/capture.py\`, \`app/api/transcription.py\`, \`stt_progress_callback_client.py\`, \`stt_completion_callback_client.py\`, and \`transcription_job_service.py\` were preserved.

\- P1 FastAPI STT restart durability is now CLOSED / FROZEN.

\- P3 timing implementation was evaluated and not selected; P3 is NO CHANGE / DEFERRED.

**### P3 — Utterance-level transcript timing — NO CHANGE / DEFERRED**

Current limitation:

\- transcript timing remains chunk-level.

\- default STT chunk size is 180 seconds.

\- one successful audio chunk currently produces one transcript result.

\- Java persists one transcript row per returned transcript result.

Current source-proven timing model:

```text

participant WAV segment
→ fixed-size audio chunk
→ STT
→ one TranscriptChunkResult per successful chunk

```

Current timestamp fields:

\- `segmentStartedAt`

\- `chunkStartMs`

\- `chunkDurationMs`

\- `absoluteStartTime`

Timeline timestamp behavior:

\- Java VOICE timestamp uses `absoluteStartTime`.

\- if `absoluteStartTime` is missing, Java falls back to `segmentStartedAt + chunkStartMs`.

\- CHAT uses `sentAt`.

\- timeline ordering remains timestamp, then VOICE before CHAT on exact tie, then source order/id tie-breaker.

Speaker identity:

\- speaker identity is derived from participant-specific Capture track / manifest identity.

\- transcript fields include `attendeeId` and `displayName`.

\- P3 is not a diarization problem.

\- no speaker recognition or diarization change is required.

Real data audit:

\- meetings inspected: `12`, `13`, `22`.

\- meeting `22`: 1 transcript row, 1 participant.

\- meeting `13`: 4 transcript rows, 1 participant.

\- meeting `12`: 3 transcript rows, 1 participant.

\- existing data did not provide real multi-participant transcript evidence.

\- meeting `12` did provide VOICE + CHAT temporal overlap evidence.

Observed limitation:

\- a VOICE transcript row represents the start time of its whole STT audio chunk.

\- if one long VOICE chunk spans multiple conversational moments and CHAT occurs inside that chunk window, the whole VOICE row sorts using the chunk start.

\- later speech inside the same chunk cannot be precisely interleaved with CHAT.

\- this is a known timestamp-granularity limitation, not a confirmed production defect.

Observed defect status:

\- no confirmed business/user chronology defect was observed.

\- no current evidence showed materially wrong decision order, materially wrong action ownership, wrong speaker attribution, generated Minutes materially harmed by chunk timing, or transcript UI materially unusable because of chronology.

Business impact classification:

\- transcript text correctness is separate from timestamp granularity.

\- P3 concerns timestamp/order precision only.

\- current speaker attribution remains intact.

\- current transcript content generation remains intact.

Final selected option:

\- keep current chunk-level timing.

\- reason: no confirmed user/business defect, participant identity is already reliable, no demonstrated harmful multi-speaker ordering, no need to expand cross-service result semantics, and zero source regression risk.

\- P3 final result: NO CHANGE / DEFERRED.

Evaluated but not selected:

\- smaller chunks may improve chronology granularity, but tradeoffs include more provider calls, more retries, higher request volume, more latency/overhead, more rate-limit pressure, and more transcript rows.

\- smaller chunk size is still not true utterance-level timing.

\- do not change `STT_CHUNK_SECONDS` without cost/quality review.

\- current provider integration consumes plain text only.

\- source does not currently request or consume segment timestamps, word timestamps, or verbose timestamp response.

\- true utterance-level timing would likely require review/change of FastAPI result semantics, `TranscriptChunkResult` semantics, the `succeededChunks` / `transcripts` count relationship, Java STT result validation, Java transcript persistence expectations, and timeline ordering/tests.

Java contract risk:

\- current invariant is one successful audio chunk → one transcript result.

\- Java validation currently depends on current chunk/result semantics.

\- changing one audio chunk into multiple utterance transcript rows requires explicit contract review.

Frontend status:

\- frontend change required now: NO.

\- current transcript UI already renders backend-provided timestamps.

\- no UI redesign is required.

\- frontend remains unchanged.

P3 introduced:

\- new DB schema: NO.

\- new API: NO.

\- new status: NO.

\- new enum: NO.

\- new dependency: NO.

\- source modification: NO.

P3 reopen conditions:

\- reopen only if real meeting evidence demonstrates meaningful chronology damage, for example multiple participants speak interleaved, VOICE + CHAT ordering materially misrepresents conversation, decision order is wrong, action ownership/order is wrong, Minutes output is materially harmed by coarse chunk timestamps, or transcript UI chronology becomes materially misleading.

\- a theoretical possibility alone is not sufficient.

\---

**# 11. Phase dependency rules**

Phases are not independent patches. They must preserve previous phase contracts.

\`\`\`text

BASELINE

   |

   v

PHASE 1

   |

   | freeze security + ownership contract

   v

PHASE 2

   |

   | must pass all Phase 1 regression checks

   v

PHASE 3

   |

   | must pass Phase 1 + Phase 2 regression checks

   v

PHASE 4

   |

   | must pass Phase 1 + 2 + 3 regression checks

   v

PHASE 5

\`\`\`

A later phase must never silently rewrite an earlier phase's ownership or lifecycle rule.

\---

**# 12. Required change protocol for every future patch**

Before CodeX is allowed to modify source:

**## STEP A — Identify target issue**

Example:

\`\`\`text

Target = C6 only

\`\`\`

**## STEP B — State invariants that must remain unchanged**

Copy relevant items from this baseline.

**## STEP C — List exact files allowed to READ**

No repo-wide scan unless explicitly justified.

**## STEP D — List exact files allowed to MODIFY**

Everything else is read-only.

**## STEP E — List cross-phase regression obligations**

Example for a Phase 3 change:

\`\`\`text

Must not break:

Phase 1 authorization

Phase 2 capture lifecycle

Current STT recovery

Minutes versioning

\`\`\`

**## STEP F — Implement smallest coherent change**

**## STEP G — Focused tests**

**## STEP H — Full project tests**

**## STEP I — Cross-service regression checks**

**## STEP J — Compare final behavior against this baseline**

Only after all checks pass may the phase be marked complete.

\---

**# 13. Mandatory regression matrix**

Every relevant phase must verify the rows that intersect its scope.

\| Scenario | Expected invariant |

\|---|---|

\| Normal meeting create/join | unchanged |

\| PRIVATE join | authorization preserved |

\| PUBLIC guest join | contract preserved |

\| End Meeting | no invalid room reactivation |

\| Capture start | one active capture |

\| Capture stop | durable Java state matches runtime result |

\| FastAPI restart during capture | recoverable / no permanent deadlock |

\| Generate Minutes | one active job |

\| STT progress | monotonic |

\| STT completion | canonical \`applySttCompletion()\` |

\| STT 404 | atomic FAILED |

\| STT COMPLETED callback lost | reconciliation resumes canonical flow |

\| STT network 500/timeout | no false failure |

\| Java restart during transcript persistence | no permanent PROCESSING orphan |

\| Stale transcript PROCESSING below max attempts | returns to PENDING without incrementing transcriptAttempts |

\| Stale transcript PROCESSING at max attempts | converges to FAILED |

\| Minutes generation failure | does not starve unrelated jobs |

\| Java crash after FastAPI accepts STT but before processingId is stored | stale PREPARING_AUDIO replays same requestId |

\| Duplicate identical STT POST | same processingId, no second task |

\| Same requestId with different STT payload | HTTP 409, existing job unchanged |

\| PREPARING_AUDIO recovery fails | existing TRANSCRIBING recovery is still attempted |

\| One scheduled FastAPI call blocks | bounded scheduler pool preserves capacity for other scheduled work |

\| Retry | no duplicate active job |

\| Reset Minutes | preserves WAV/manifest |

\| Version editing | creates new version as designed |

\| FINAL | historical version not overwritten |

\| Participant mutation | backend permission enforced |

\| LiveKit webhook | authentic + maps correct connection |

\| FE transcript | reflects real backend data after Phase 4 |

\---

**# 14. Phase completion gate**

A phase is COMPLETE only when all are true:

\- Target issue is fixed.

\- No unrelated behavior was changed.

\- No new duplicate orchestration path exists.

\- Previous phase invariants still hold.

\- Focused tests pass.

\- Full tests pass.

\- Cross-service contract tests pass where relevant.

\- No new enum/schema/API was added without explicit baseline approval.

\- Modified-file scope matches the approved prompt.

\- This baseline does not need an unreviewed semantic rewrite.

If any check fails:

\`\`\`text

DO NOT continue to next phase.

\`\`\`

\---

**# 15. Baseline update rule**

This file should be updated only in these cases:

1\. A phase intentionally changes a system contract.

2\. A source change introduces a new canonical component.

3\. Runtime evidence proves an audited invariant is incorrect.

4\. A Phase issue is completed and its final behavior should be frozen.

Do NOT update the baseline merely to make new code look compliant.

The code must comply with the baseline first.

\---

**# 16. How to use this file with ChatGPT / CodeX**

For every future task, provide:

\`\`\`text

1\. Current 3 source snapshots

2\. This LIVEKIT\_SYSTEM\_BASELINE.md

3\. Target issue ID, e.g. C6

\`\`\`

Instruction:

\`\`\`text

Audit the requested issue against LIVEKIT\_SYSTEM\_BASELINE.md first.

Do not modify code until you confirm:

\- canonical flow,

\- ownership boundary,

\- previous phase invariants,

\- exact allowed file scope.

After implementation, report every baseline invariant touched

and prove previous completed phases remain valid.

\`\`\`

This makes the baseline the persistent anchor instead of relying on conversational memory.

\---

**# 17. Current recommended execution order**

\`\`\`text

PHASE 1 — FROZEN

C1 + C2 + C3 + C4 + C7

do not reopen without explicit baseline review





PHASE 2 — FROZEN

C5 + C6 + C8 + I6

do not reopen without explicit baseline review





PHASE 3 — FROZEN

I1 + I2 + I3 + I4

do not reopen without explicit baseline review





PHASE 4 — FROZEN

I5 + I7 + I8

+ source-integrity cleanup

do not reopen without explicit baseline review




PHASE 5 — FROZEN

production hardening only

P2 — FROZEN
↓
P1 — FROZEN
↓
P3 — NO CHANGE / DEFERRED

No Phase 5 coding work remains.

\`\`\`

Within a phase, issues should still be implemented as small scoped patches,

but every patch must be checked against the whole baseline before completion.

\---

**# 18. Phase 1 freeze record**

**\*\*Frozen on:\*\*** 2026-09-07

**\*\*Audited snapshots:\*\***

\- \`meeting-frontend(20260907-072621).zip\`

\- \`src(9).zip\`

\- \`app(6).zip\`

Phase 1 freeze gate result:

\`\`\`text

C1 Legacy AI flow                  PASS / FROZEN

C2 Participant authorization       PASS / FROZEN

C3 Direct LiveKit exposure         PASS / FROZEN

C4 Public Meeting privacy/access   PASS / FROZEN

C7 Webhook/connection lifecycle    PASS / FROZEN

\`\`\`

Frozen Phase 1 interaction:

\`\`\`text

Browser

→ /join/{meetingId}

→ Java join-info

→ Java /join

→ MeetingEntity aggregate lock

→ connection PENDING

→ Java-issued LiveKit token

→ LiveKit

→ verified webhook

→ same MeetingEntity aggregate lock

→ connection CONNECTED / LEFT / REMOVED

\`\`\`

Regression obligations for every Phase 2+ patch:

\- Do not reintroduce legacy AI/Capture orchestration.

\- Do not reintroduce direct frontend or public Java LiveKit infrastructure bypass.

\- Do not expose full meeting management DTOs anonymously.

\- Do not weaken participant meeting-level authorization.

\- Do not bypass official LiveKit webhook verification.

\- Do not move \`CONNECTED\` creation back into \`/join\`.

\- Do not remove the aggregate meeting lock from participant lifecycle writers.

\- Do not allow \`REMOVED\` participant/connection state to resurrect.

\- Preserve Phase 1 public/private join behavior.

\- Preserve all pre-existing STT, Capture, Transcript, Minutes, retry/reset, and versioning KEEP-AS-IS contracts.

Historical open-items snapshot recorded at Phase 1 freeze time:

\`\`\`text

PHASE 2 (now CLOSED / FROZEN):

C5 terminal meeting join

C6 end meeting ↔ Capture ↔ Minutes

C8 Capture restart recovery

I6 end permission contract

PHASE 3:

I1 transcript PROCESSING recovery

I2 Minutes generation starvation

I3 PREPARING\_AUDIO idempotency design

I4 scheduler isolation

PHASE 4:

I5 meeting status filtering

I7 real transcript UI

I8 participantKey FE ↔ BE

PHASE 5:

FastAPI persistence/scale

internal-key deployment hardening

utterance-level timing

\`\`\`

The frontend production build was not conclusively observed in the Phase 1 execution environment because the Next.js build process hung / previously hit \`.next/trace\` environment issues. Focused frontend tests and TypeScript typecheck passed. This is retained as an environment validation note, not a Phase 1 source-contract failure.

\---

**# 19. Phase 2 freeze record**

**\*\*Frozen on:\*\*** 2026-09-08

**\*\*Audited final snapshots:\*\***

\- \`meeting-frontend(20260908-021846).zip\` + \`data(1).zip\` snapshot supplement

\- \`meeting-service(20260908-021916).zip\`

\- \`meeting-fastapi(7).zip\`

Snapshot-integrity note:

\- the frontend archive omitted the existing \`data/\` folder during packaging.

\- \`data(1).zip\` supplied the missing frontend files:

\- \`data/mockManagementMinutes.ts\`

\- \`data/mockManagementMinutes.test.ts\`

\- \`data/mockMeetings.ts\`

\- all three files matched the prior audited frontend snapshot byte-for-byte.

\- after overlay, frontend local \`@/...\` import validation reported no unresolved imports.

\- this was a packaging/snapshot issue, not a source-code regression.

Phase 2 freeze gate result:

\`\`\`text

C5 Terminal meeting join                  PASS / FROZEN

C6 End Meeting ↔ Capture ↔ Minutes        PASS / FROZEN

C8 Capture restart recovery               PASS / FROZEN

I6 End permission contract                PASS / FROZEN

Phase 1 regression                        PASS

Cross-service consistency                 PASS

Snapshot integrity                        PASS

PHASE 2 FREEZE AUDIT                      PASS

\`\`\`

Frozen Phase 2 canonical flow:

\`\`\`text

Terminal join

→ MeetingEntity aggregate lock

→ reject COMPLETED / CANCELLED / time-derived COMPLETED

→ no realtime re-entry

End Meeting

→ MeetingEntity PESSIMISTIC\_WRITE

→ requireCanEndMeeting

→ Capture consistency/finalization

→ durable Meeting COMPLETED

→ best-effort room cleanup

Minutes readiness

→ persisted explicit Meeting COMPLETED

→ no active/unresolved newer Capture

→ valid completed manifest set

→ one meeting-level STT pipeline

Capture recovery

→ Java durable capture row

↔ exact FastAPI runtime

↔ exact manifest

→ captureSessionId correlation

→ reconciliation only

→ no false FAILED on transport uncertainty

→ no replacement auto-start

→ no auto-end Meeting

End permission

→ ADMIN / MEETING\_MANAGER / creator / CHAIRMAN

→ backend-authoritative

→ canManageMeeting remains ADMIN / creator / CHAIRMAN

→ frontend visibility derives from join.permissions.canEndMeeting

\`\`\`

Regression obligations for every Phase 3+ patch:

\- Preserve all frozen Phase-1 ownership, security, privacy, participant, webhook, and connection-lifecycle contracts.

\- Do not allow terminal meetings to re-enter realtime lifecycle.

\- Do not bypass \`endMeeting()\` as the canonical durable transition to \`COMPLETED\`.

\- Preserve Capture-before-Meeting-completion ordering.

\- Preserve explicit persisted \`COMPLETED\` as a Minutes readiness prerequisite.

\- Preserve newer/active Capture blocking semantics for Minutes.

\- Preserve multiple valid completed manifests feeding one meeting-level STT request.

\- Preserve \`captureSessionId\` as the exact Java ↔ FastAPI ↔ manifest correlation key.

\- Do not replace exact Capture recovery with latest-manifest guessing.

\- Do not false-fail Capture on network/timeout/5xx uncertainty.

\- Do not auto-start replacement Capture from recovery.

\- Do not auto-complete Meeting from Capture recovery.

\- Preserve in-flight FastAPI \`STARTING\` runtime visibility.

\- Preserve canonical End Meeting permission: \`ADMIN\`, \`MEETING\_MANAGER\`, creator, \`CHAIRMAN\`.

\- Preserve \`canManageMeeting = ADMIN | creator | CHAIRMAN\`.

\- Do not let \`MEETING\_MANAGER\` End permission expand participant-management permission.

\- Keep frontend End Meeting permission backend-driven through \`join.permissions.canEndMeeting\`.

Phase 2 is now frozen.

Any Phase 3+ task that needs to alter a Phase-2 invariant above must STOP and explicitly review/update this baseline first.

\---

**# 20. Phase 3 freeze record**

**\*\*Frozen on:\*\*** 2026-09-08

**\*\*Audited final snapshots:\*\***

\- \`meeting-frontend(20260908-021846).zip\` + \`data(1).zip\` snapshot supplement — frontend unchanged during Phase 3

\- \`meeting-service(20260908-073934).zip\`

\- \`meeting-fastapi(10).zip\`

Phase 3 implementation sequence:

\`\`\`text

I1 Transcript PROCESSING crash recovery

→ focused tests PASS

→ Java full suite PASS

I2 Minutes generation starvation

→ focused tests PASS

→ correction preserved dispatcher error boundary

→ Java full suite PASS

I3 PREPARING_AUDIO idempotency/recovery

→ Java focused/full tests PASS

→ FastAPI focused/full tests PASS

→ correction isolated PREPARING recovery failure from existing TRANSCRIBING recovery

I4 Scheduler isolation

→ application.yml only

→ Java full suite PASS

\`\`\`

Final reported regression results at Phase 3 freeze:

\`\`\`text

Java full test suite                         433 PASS / 0 failure / 0 error

FastAPI full test suite after I3             126 PASS

I1 source/diff audit                          PASS

I2 source/diff audit                          PASS

I3 Java ↔ FastAPI contract audit              PASS

I4 one-file scheduler config diff audit       PASS

Phase 1 frozen contracts                      PRESERVED

Phase 2 frozen contracts                      PRESERVED

Frontend source during Phase 3                UNCHANGED

PHASE 3 FREEZE AUDIT                          PASS

\`\`\`

Frozen Phase 3 canonical processing behavior:

\`\`\`text

STT submission

→ Java durable Minutes jobId

→ requestId = minutes-job-{jobId}

→ FastAPI idempotent POST

→ same logical replay = same processingId

→ conflicting replay = HTTP 409



Java crash at PREPARING_AUDIO

→ stale lock/recheck

→ replay same requestId

→ markTranscribing only if state still matches

→ existing TRANSCRIBING reconciliation



Java crash during transcript persistence

→ stale PROCESSING lock/recheck

→ below max: transcriptStatus PENDING

→ same sttProcessingId

→ existing persistence path

→ at max: FAILED



Minutes generation exception

→ generation transaction rollback

→ separate lock/recheck failure transition

→ failed job leaves eligible queue

→ next job can proceed



Scheduled processing

→ bounded Spring scheduling pool

→ default size 5

→ SCHEDULER_POOL_SIZE override

→ no parallel orchestration architecture

\`\`\`

Regression obligations for every Phase 4+ patch:

\- Preserve deterministic STT requestId identity: \`minutes-job-{jobId}\`.

\- Do not make stale PREPARING_AUDIO recovery generate a new logical STT request.

\- Do not allow identical requestId replay to create duplicate FastAPI STT tasks.

\- Preserve HTTP \`409\` for requestId payload conflict.

\- Preserve FastAPI's current single-active-instance limitation; Phase 5 / P1 added durable local STT checkpoints but did not add distributed/multi-instance STT ownership.

\- Preserve transcript PROCESSING stale recovery: lock/recheck, no attempt increment during recovery, same sttProcessingId, canonical PENDING path below max attempts.

\- Do not let transcript recovery start STT or generate Minutes.

\- Preserve generation-failure starvation fix and its separate transaction after generation rollback.

\- Do not make a failed generation job remain indefinitely eligible.

\- Preserve PREPARING_AUDIO recovery failure isolation from existing TRANSCRIBING recovery.

\- Preserve bounded scheduler pool configuration unless an explicit scheduler redesign is reviewed against this baseline.

\- Do not use Phase 4 UI/contract work to rewrite Phase 3 processing ownership or state transitions.

\- Preserve all Phase 1 and Phase 2 frozen contracts.

Phase 3 is now frozen.

Any Phase 4+ task that needs to alter an I1/I2/I3/I4 invariant above must STOP and explicitly review/update this baseline first.

**### Phase 3 runtime-validation note**

Phase 3 was frozen from source-diff audit plus deterministic focused/full automated tests.

The earlier Phase 2 runtime smoke validated the core live meeting/Capture/End flow, but no additional destructive Java/FastAPI crash-restart runtime smoke was recorded as part of the Phase 3 freeze.

Do not later describe destructive Phase 3 runtime crash/restart testing as completed unless it is actually executed and recorded.

\---

**# 21. Phase 4 freeze record**

**\*\*Frozen on:\*\*** 2026-09-09

**\*\*Audited final snapshots:\*\***

\- \`meeting-frontend(20260908-094725).zip\`

\- \`src(20260908-094741).zip\`

\- \`meeting-fastapi(20260908-094706).zip\`

Phase 4 freeze gate result:

\`\`\`text

I5 Meeting status filtering                PASS / FROZEN

I7 Real transcript UI                      PASS / FROZEN

I8 participantKey FE ↔ BE                  PASS / FROZEN

CLEAN-1 Legacy recording path              PASS / FROZEN

CLEAN-2 Mock Meeting lifecycle             PASS / FROZEN

CLEAN-3 Production mock Minutes dependency PASS / FROZEN

CLEAN-4A Dead starter architecture         PASS / FROZEN

CLEAN-4B Obsolete config documentation     PASS / FROZEN

Phase 1 regression                         PASS

Phase 2 regression                         PASS

Phase 3 regression                         PASS

Cross-service canonical ownership          PASS

PHASE 4 FREEZE AUDIT                       PASS

\`\`\`

Final Phase 4 canonical outcomes:

\`\`\`text

Effective meeting list status

DB query filtering
+
response mapping
→ same effective status semantics

without mutating persisted lifecycle state.



Transcript UI

Java/PostgreSQL persisted transcript
→ frontend

no production mock transcript.



Participant mutation

userId
→ logical identity/diff matching

participantKey
→ persisted mutation locator.



Capture UI

manual button only
→ Java
→ FastAPI

no automatic recording path.



Meeting data

frontend
→ Java
→ PostgreSQL

no localStorage/mock parallel lifecycle.

\`\`\`

Final observed validation entering Phase 5:

\- Frontend: \`npx tsc --noEmit\` = PASS.

\- Frontend: \`npm test\` = PASS; 9 test files; 80 tests PASS.

\- Java: \`mvn test\` = PASS; 443 tests; 0 failures; 0 errors.

\- FastAPI: \`pytest\` = PASS; 126 tests PASS.

Environment/sandbox EPERM or pytest cache warnings are environment notes, not source-contract failures.

No destructive Java/FastAPI crash-restart runtime smoke was recorded as part of the Phase 4 freeze. That gap was later closed by Phase 5 / P1.

Phase 5 completed while preserving all Phase 1–4 frozen contracts.

Current Phase 5 status:

\- P2 — internal key deployment/authentication hardening — FROZEN.

\- P1 — FastAPI STT single-instance restart durability — FROZEN.

\- P3 — utterance-level timing — NO CHANGE / DEFERRED.

Phase 5 freeze constraints:

\- P1 must NOT move durable business ownership away from Java/PostgreSQL.

\- P1 must NOT claim horizontal multi-instance Capture safety merely by persisting STT metadata.

\- P2 must NOT introduce an unrelated authentication architecture unless explicitly reviewed.

\- P3 remains NO CHANGE / DEFERRED and must not change transcript/schema/UI merely for theoretical precision without demonstrated need.

\- frontend/UI remains KEEP-AS-IS unless an explicitly reviewed future target proves a frontend change is required.

Phase 4 is now frozen.

Any future task that needs to alter a Phase-1, Phase-2, Phase-3, Phase-4, or Phase-5 invariant above must STOP and explicitly review/update this baseline first.

**## Phase 5 / P2 freeze record**

**\*\*Frozen on:\*\*** 2026-09-10

Phase 5 P2 freeze gate result:

\`\`\`text

Java callback blank expected key          PASS

Java callback missing key                 PASS

Java callback wrong key                   PASS

Java callback exact key                   PASS

FastAPI REQUIRE=false                     PASS

FastAPI REQUIRE=true missing key          PASS

FastAPI REQUIRE=true wrong key            PASS

FastAPI REQUIRE=true exact key            PASS

Java full regression                      PASS

FastAPI full regression                   PASS

Frontend unchanged                        PASS

Phase 1–4 frozen contracts                PRESERVED

P2 INTERNAL KEY HARDENING                 PASS / FROZEN

\`\`\`

Frozen P2 result:

\- Java internal STT callback authentication now fails closed when Java's configured \`ai-service.internal-api-key\` is blank.

\- missing, blank, or wrong callback \`X-Internal-Api-Key\` is rejected with HTTP \`401\`.

\- exact matching callback \`X-Internal-Api-Key\` continues to the existing state service.

\- Java ↔ FastAPI service authentication remains the existing shared \`X-Internal-Api-Key\` contract.

\- \`SecurityConfig\` browser/session semantics remain unchanged.

\- FastAPI \`GET /health\` remains public.

\- FastAPI Capture/STT route authentication remains owned by existing \`require_internal_api_key\`.

\- production deployment must set matching Java \`AI_INTERNAL_API_KEY\` and FastAPI \`INTERNAL_API_KEY\`, with FastAPI \`REQUIRE_INTERNAL_API_KEY=true\`.

\- callback authentication failures do not create new STT, Capture, Minutes, or transcript business failure semantics.

\- P1 FastAPI STT restart durability is now CLOSED / FROZEN.

\- P3 utterance-level timing is NO CHANGE / DEFERRED.

\---

**## Phase 5 / P1 freeze record**

**\*\*Frozen on:\*\*** 2026-09-11

Phase 5 P1 freeze gate result:

```text

Source design audit                         PASS
P1 implementation                          PASS
Durable-store fail-closed validation        PASS
FastAPI regression                          PASS
Java regression                             PASS
RequestId replay after hard restart         PASS
409 conflict after restart                  PASS
Progress high-water                         PASS
Graceful shutdown durability                PASS
FAILED terminal result durability           PASS
COMPLETED terminal result durability        PASS
Java sttProcessingId hard-restart survival  PASS
False FastAPI 404 after restart             NO
Java transcript persistence                 PASS
Minutes pipeline convergence                PASS
Phase 1-4 regression                        PASS
P2 preserved                                PASS
Application source integrity                PASS

P1 FASTAPI STT RESTART DURABILITY           PASS / FROZEN

```

Frozen P1 result:

\- FastAPI STT request identity and processing result checkpoints are durable across a single-instance FastAPI process restart.

\- Java keeps canonical durable business ownership.

\- deterministic Java request identity remains `requestId = "minutes-job-" + jobId`.

\- requestId replay across restart returns the same processingId.

\- conflicting requestId identity reuse returns HTTP `409`.

\- Java definitive FastAPI `404` semantics remain unchanged because P1 prevents false Python-side `404` after restart.

\- real Java E2E destructive restart proof passed for meeting `22`, job `9`, processingId `0c475902-2317-44de-9de0-26e842ac569e`.

\- FastAPI result completed `1/1`; Java transcript rows = 1; Minutes Version 1 `DRAFT` was created.

\- P1 does not imply horizontal FastAPI or Capture scaling safety.

\- P3 utterance-level timing is NO CHANGE / DEFERRED.

\---

**## Phase 5 / P3 decision record**

**\*\*Decision date:\*\*** 2026-09-11

Decision gate:

```text

Current timing model audited                     PASS
Chunk size confirmed                             PASS
Java transcript persistence audited              PASS
VOICE + CHAT timeline audited                    PASS
Participant identity preserved                   PASS
Diarization required                             NO
Real transcript data inspected                   PASS
Confirmed multi-participant chronology defect    NO
Confirmed business impact                        NO
Smaller-chunk option evaluated                    YES / NOT SELECTED
Utterance-level option evaluated                  YES / NOT SELECTED
Source modification required                     NO

P3 IMPLEMENTATION JUSTIFIED                      NO
P3 FINAL STATUS                                  NO CHANGE / DEFERRED

```

Final P3 result:

\- current chunk-level transcript timing is retained.

\- default `STT_CHUNK_SECONDS` remains `180`.

\- one successful audio chunk remains one transcript result.

\- Java persists one transcript row per returned transcript result.

\- speaker identity remains participant track / manifest derived.

\- P3 is not diarization.

\- no source, schema, API, enum, dependency, STT model, STT chunk-size, Java, FastAPI, frontend, or UI change was made for P3.

\- P3 may be reopened only if real meeting evidence demonstrates meaningful chronology damage; theoretical precision alone is not sufficient.

\---

**# Phase 5 freeze record**

**\*\*Frozen on:\*\*** 2026-09-11

Final Phase 5 gate:

```text

P2 Internal key hardening                        PASS / FROZEN
P1 FastAPI STT restart durability                PASS / FROZEN
P3 timing decision                               NO CHANGE / DEFERRED

Phase 1 regression                              PASS
Phase 2 regression                              PASS
Phase 3 regression                              PASS
Phase 4 regression                              PASS

Java durable business ownership                  PRESERVED
FastAPI runtime ownership                        PRESERVED
Frontend ownership                               PRESERVED
Capture contract                                 PRESERVED
Minutes contract                                 PRESERVED
Internal authentication                          PRESERVED
Application source integrity                     PASS

PHASE 5                                          PASS / FROZEN

```

Final post-Phase-5 architecture:

```text

Frontend
→ UI / API consumer

Java + PostgreSQL
→ durable business source of truth

FastAPI
→ Capture/STT runtime executor

FastAPI STT
→ durable LOCAL restart checkpoint
→ single active instance assumption

P2
→ shared X-Internal-Api-Key service authentication

P3
→ current chunk-level timing retained

```

No alternate orchestration path exists.

Current FastAPI limitation:

\- FastAPI STT restart durability is now provided for one active instance.

\- runtime task ownership remains process-local.

\- Capture runtime remains process-local.

\- horizontal multi-instance execution safety is not guaranteed.

\- no Redis/distributed runtime exists.

Future change rule after Phase 5 freeze:

\- any future change touching P1 STT durability, P2 internal auth, P3 transcript timing, Capture recovery, Java Minutes recovery, participant identity, or timeline ordering must first review this baseline.

\- do not silently reopen a frozen/closed item.

\---

**# 22. Final principle**

The goal is not:

\`\`\`text

make each patch pass

\`\`\`

The goal is:

\`\`\`text

make every patch preserve the whole system.

\`\`\`

A later phase is not allowed to "fix" its local problem by breaking an earlier

ownership, lifecycle, security, recovery, or persistence invariant.

**\*\*This document is the anchor for all future LiveKit Meeting development.\*\***

## 2026-10-08 — Authorized 24F remediation addendum

The user explicitly authorized remediation of all 24 production audit findings while preserving the agreed business behavior. The phase history above remains a historical baseline. Current remediation contracts and verification evidence are recorded in `meeting-service/docs/AUDIT24_RELEASE.md`, `meeting-fastapi/docs/PRODUCTION_RUNTIME.md` and `output/audit24/STATUS.md`.

The supported Python runtime now enforces one storage owner/worker; durable STT receipts and unknown paid-call outcomes survive restart. Java/HTTP capture correlation and the existing capture/STT/AI Result payloads remain. Chat uses authoritative persisted history, all creation modes support durable client request IDs, and draft writes require the observed revision. Terminal cleanup is durable; explicit admin reopen is serialized with cleanup. PUBLIC/PRIVATE/POINT permission contracts remain those finalized by the user. Email and extraction into three independently deployed Java services remain deferred.
