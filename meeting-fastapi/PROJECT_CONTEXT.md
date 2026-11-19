# PROJECT_CONTEXT.md — meeting-fastapi

> Project: `meeting-fastapi`  
> Stack: FastAPI / Python / LiveKit / OpenAI STT  
> Purpose: Capture + post-processing STT context, algorithms, API contracts, implementation status, and decisions.
>
> ## Codex maintenance rule
> After every task that modifies this repository, Codex MUST update this file before finishing.
>
> Update only affected sections:
> - `Current implementation`
> - `STT pipeline`
> - `API contracts`
> - `Algorithm`
> - `Known constraints`
> - `Next work`
> - `Change log`
>
> Do not rewrite unrelated sections. Do not change Capture while working only on post-processing unless explicitly required.

---

# 1. Responsibility of this repository

`meeting-fastapi` has two distinct responsibilities:

## Capture

```text
LiveKit audio
→ participant track
→ WAV
→ manifest.json
```

## Post-processing

```text
manifest(s)
→ WAV
→ chunk
→ STT
→ transcript
→ progress/result
```

Python does NOT own:
- meeting business state
- Chat persistence
- Minutes versions
- final DB persistence

Those belong to Java.

---

# AI conclusion current implementation (2026-10-07)

- New `/api/ai/conclusions/generate` route always requires X-Internal-Api-Key,
  independently of the existing capture/STT development flag. Java owns meeting
  state, permissions, the one-success limit, job state and minutes persistence.
- Typed bounded context contains meeting metadata, current minutes, CLOSED poll
  counts/descriptions, published questions with written answers, chat opinions,
  and preserved manual conclusion. All input is treated as data by the prompt.
- AsyncOpenAI Responses Structured Outputs returns summary/decisions/
  pollConclusions/actionItems/unresolvedIssues. Source references must exist;
  named assignees and literal dates must occur in the context. Unknown assignments
  or deadlines are null. Exact poll statements are rebuilt from supplied counts.
- UUID/context-hash receipts under conclusion_root_dir are atomically persisted
  with fsync/replace. An OS file lock prevents parallel provider requests and is
  released on process death. Same successful request is read from disk after
  service restart; a changed context for the same UUID conflicts. No in-memory
  background queue or changes to existing capture/STT manager/worker behavior.
- OPENAI_API_KEY remains server-only. CONCLUSION_MODEL defaults gpt-4o-mini;
  timeout 90s, max input 150000 characters, max output 12000 tokens. Provider
  errors are sanitized; failed calls write no successful receipt and can retry.
- Validation: 221 pytest tests passed. New coverage includes cache/restart,
  parallel lock, failure/retry, wrong source/assignee/date/schema, internal auth,
  deterministic maximum-size poll output and existing capture/STT regression.
  Real OpenAI smoke passed with synthetic data only and identical cached repeat;
  evidence: test_output/conclusion-provider-smoke/result.json.
- This implements AI Result conclusion; full LLM-generated initial minutes are
  a separate existing pipeline concern. See README and Java docs/AI_RESULT.md.

Change log — 2026-10-07: Add internal structured AI conclusion endpoint, validated
source references, durable idempotent receipts and provider/configuration tests.

---

# 2. Capture implementation

## Current implementation / production hardening — 2026-10-08

- Capture subscribes microphones explicitly with auto_subscribe disabled. SDK
  frame/write queues are bounded and overflow fails visibly. Batched WAV IO is
  off the event loop; manifest writes coalesce and fsync/replace off-loop.
- Track initializer/connect/stop tasks have owned lifetimes. Final disconnect
  precedes terminal publication even for delayed connect. Failed cleanup retains
  session ownership, rejects replacement and retains the OS owner until process
  exit if shutdown cannot finish. Permanent disconnect/recorder/storage failure
  remains FAILED after stop; transient reconnect is RUNNING with gap warning.
- One worker owns capture/STT storage, enforced by OS lock. Restart marks stale
  active capture manifests FAILED and repairs recorder-format PCM WAV headers
  from surviving bytes; it does not recover lost audio or rejoin old captures.
- STT provider/preprocessing/job quotas are process-wide; VAD/planning/IO run
  off-loop. Job admission/callback queues/terminal caches/body/input are bounded.
  Async client lifetimes and cancelled disk operations are drained explicitly.
- Durable primary job JSON is authoritative; compact request/status indexes
  rebuild at startup and repair partial sidecar failures. Terminal result bodies
  are loaded only on demand, not retained indefinitely in RAM.
- Successful paid chunks have durable content-bound receipts. A durable dispatch
  marker prevents an unknown provider outcome after crash from being charged
  again automatically; it requires manual reconciliation. Only explicit 429 is
  retryable; timeouts/transport/5xx are conservative nonretryable failures.
- Production defaults to authenticated internal routes and rejects missing or
  default internal key / missing LiveKit credentials. Local auth optout requires
  explicit development environment. Unexpected response details are sanitized;
  validation responses omit rejected raw input.
- Java endpoint paths, request/response fields, voice identity/offset and AI
  conclusion behavior are retained. Callbacks are advisory; durable GET terminal
  results support Java scheduled recovery. See docs/PRODUCTION_RUNTIME.md.
- Final validation: 278 pytest cases passed (221 baseline + 57 new hardening
  cases), including existing AI conclusion behavior. Durable report/log:
  test_output/audit24-final-junit.xml and test_output/audit24-final-pytest.txt.
  All 69 app/test Python sources parsed successfully with Python AST.

Change log — 2026-10-08: Resolve audit F04/F05/F13/F18/F19/F20 and Python F24,
including paid-dispatch ambiguity and cancellation-safe persistent job ordering.
Full verification uses isolated fake providers/media; no production connection
or real 100-person/two-hour media capacity claim is made.

Capture stores:

```text
data/meetings/
└── meeting-{id}/
    └── session-{uuid}/
        ├── manifest.json
        └── participants/
            └── {participant}/
                ├── segment-001_....wav
                └── segment-002_....wav
```

Current WAV format:

```text
48 kHz
mono
16-bit PCM
```

Manifest contains metadata such as:

```text
meetingId
sessionId
participant
attendeeId
displayName
segmentId
segmentIndex
trackSid
filePath
startedAt
endedAt
durationMs
status
```

## Important segment-status rule

Do NOT filter STT only on:

```text
segment.status == COMPLETED
```

Capture stop may leave a valid WAV with segment status `CANCELLED`.

Meeting 12 already proved such WAVs are readable and transcribable.

STT validity should rely on:
- `filePath` exists.
- path is inside capture root.
- file exists.
- file is WAV.
- WAV can be planned/read.

---

# 3. STT provider abstraction

Current abstraction:

```text
SttProvider
     │
     └── OpenAISttProvider
```

Current provider:

```env
STT_PROVIDER=openai
STT_MODEL=gpt-4o-mini-transcribe
```

Current STT call concept:

```text
WAV
→ OpenAI Speech-to-Text
→ Vietnamese text
```

Future option:

```text
FasterWhisperProvider
```

The goal is to allow provider replacement without changing Java contracts.

---

# 4. One-segment STT

Endpoint:

```http
POST /api/postprocessing/stt/segments/transcribe
```

Input identifies:
- meeting
- manifest
- segment

It:
1. resolves manifest safely under `CAPTURE_ROOT_DIR`.
2. validates meetingId.
3. finds segment.
4. resolves WAV.
5. calls STT.
6. returns text + speaker/timing metadata.

This was tested successfully using real meeting 12 audio.

---

# 5. Audio chunking

Config:

```env
STT_CHUNK_SECONDS=180
```

WAV <= threshold:

```text
use original WAV
temporary=False
```

WAV > threshold:

```text
plan chunks
→ materialize only the chunk currently needed
→ STT
→ cleanup temp chunk
```

Do not generate all chunk files in advance.

Chunk metadata includes:

```text
chunkIndex
startMs
durationMs
```

Original WAV must never be deleted.

---

# 6. Queue + bounded concurrency

Config:

```env
STT_CONCURRENCY=4
```

Queue:

```python
asyncio.Queue(
    maxsize=STT_CONCURRENCY * 2
)
```

Default:

```text
queue max waiting items = 8
workers = 4
```

Important rule:

Do NOT do:

```python
await asyncio.gather(*all_stt_requests)
```

for hundreds of chunks.

Correct:

```text
all work
   ↓
bounded queue
   ↓
Worker 1
Worker 2
Worker 3
Worker 4
```

At most 4 STT calls are active at a time.

---

# 7. Retry algorithm

Config:

```env
STT_MAX_RETRIES=2
STT_RETRY_BASE_SECONDS=1.0
```

Per chunk:

```text
attempt 1
retry 1
retry 2
```

Max attempts:

```text
3
```

Backoff:

```text
retry1 = base
retry2 = base * 2
```

A failed chunk does NOT cause the whole manifest/meeting to be retranscribed.

---

# 8. Transcript timing

Each successful chunk retains:

```text
speaker
segment
chunk
segmentStartedAt
chunkStartMs
```

Calculate:

```text
absoluteStartTime
=
segmentStartedAt
+
chunkStartMs
```

This allows later sorting into a meeting timeline.

---

# 9. Manifest-level STT

Current implementation can process one manifest:

```text
manifest
→ all participants
→ all segments
→ chunk plans
→ one queue
→ four workers
→ retry
→ transcript
```

Status:

```text
COMPLETED
PARTIAL
FAILED
```

---

# 10. Progress algorithm

A chunk becomes terminal when:
- STT succeeds, or
- all retries fail.

Then:

```text
processedChunks += 1
```

Formula:

```text
percent = processedChunks / totalChunks * 100
```

Initial progress callback has been fixed so total chunks are known before first chunk completes.

Real test meeting 12:

```text
0/2   0%
1/2  50%
2/2 100%
```

---

# 11. Async STT job

Implemented.

Endpoints:

```http
POST /api/postprocessing/stt/jobs
GET  /api/postprocessing/stt/jobs/{processingId}
GET  /api/postprocessing/stt/jobs/{processingId}/result
```

Current job engine:
- creates UUID `processingId`.
- stores job in-memory.
- uses `asyncio.create_task`.
- returns HTTP 202 immediately.
- STT continues after request connection closes.
- exposes progress/status/result.

Status flow:

```text
QUEUED
→ PROCESSING / TRANSCRIBING
→ COMPLETED
or PARTIAL
or FAILED
```

## Real async test

Meeting 12 passed:

```text
POST → QUEUED
GET  → PROCESSING 0/2
GET  → PROCESSING 1/2
GET  → COMPLETED 2/2
GET result
→ 2 succeeded
→ 0 failed
```

This proves:
- HTTP is not held open during STT.
- background task continues.
- status/result APIs work.

---

# 12. Worker lifecycle

Workers are created with `asyncio.create_task`.

Lifecycle is protected with:

```text
try
→ queue processing
finally
→ cancel unfinished workers
→ gather(..., return_exceptions=True)
```

If FastAPI reloads/shuts down while STT is active:
- parent job cancels.
- child workers cancel.
- temporary chunks cleanup through `finally`.

---

# 13. Current job storage limitation

Jobs are currently:

```text
in-memory
```

This is intentional for development.

Limitation:

```text
FastAPI restart
→ job status/result metadata lost
```

DB/Redis/Celery is deferred.

---

# 14. Meeting-level / Multi-manifest — agreed target

A meeting can have multiple capture sessions:

```text
Meeting 12
├── session A → manifest A
├── session B → manifest B
└── session C → manifest C
```

Target Python contract:

```json
{
  "meetingId": 12,
  "manifestPaths": [
    "meeting-12/session-a/manifest.json",
    "meeting-12/session-b/manifest.json",
    "meeting-12/session-c/manifest.json"
  ]
}
```

## Core rule

One meeting must create:

```text
1 Python processingId
1 common queue
1 worker pool
1 progress stream
1 transcript result
```

NOT:

```text
1 job per manifest
```

## Global concurrency

Example:

```text
A = 100 chunks
B = 80 chunks
C = 120 chunks
```

All become:

```text
300 chunks
→ one queue
→ only 4 workers total
```

Never:

```text
4 workers × manifest count
```

---

# 15. Meeting-level transcript target

Every successful transcript chunk needs:

```text
sessionId
attendeeId
speakerName
segmentId
segmentIndex
chunkIndex
chunkStartMs
chunkDurationMs
segmentStartedAt
absoluteStartTime
filePath
text
```

Failure also needs:

```text
sessionId
segmentId
chunkIndex
filePath
attempts
error
```

Planning-level failure may use:

```text
chunkIndex = None
attempts = 0
```

---

# 16. Meeting-level failure isolation target

Manifest-level structural failures may fail the request:
- manifest missing.
- manifest outside capture root.
- invalid JSON.
- meetingId mismatch.

WAV-level problems should NOT kill the whole meeting:

```text
WAV1 ✅
WAV2 ✅
WAV3 ❌ corrupt
WAV4 ✅
```

Expected:

```text
WAV1/WAV2/WAV4 transcribed
WAV3 → failures[]
status = PARTIAL
```

---

# 17. Meeting-level progress target

Total:

```text
all chunk work items
+
planning-level WAV failures
```

Example:

```text
299 STT chunks
1 corrupt WAV planning failure
→ total work = 300
```

Planning failure is already terminal.

Initial:

```text
1/300
```

then remaining work increments until:

```text
300/300
```

For no planning failures:

```text
0/N
→ ...
→ N/N
```

---

# 18. Meeting-level sorting target

Sort transcript by:

```text
1. absoluteStartTime
2. sessionId
3. segmentIndex
4. chunkIndex
```

Items with no `absoluteStartTime` go last.

This produces a unified Voice Transcript timeline for Java.

---

# 19. Internal API security

Existing configuration:

```env
INTERNAL_API_KEY=...
REQUIRE_INTERNAL_API_KEY=false
```

Meeting-level STT router should use the existing internal API-key dependency.

Production:

```text
Java
→ X-Internal-Api-Key
→ FastAPI
```

Local dev with:

```env
REQUIRE_INTERNAL_API_KEY=false
```

continues to work without the header.

Do not create another auth mechanism.

---

# 20. Chat rule

Python does NOT receive Chat for STT.

After STT:

```text
Python Voice Transcript
      ↓
Java
      +
Java meeting_chat_messages
      ↓
Meeting Timeline
```

Chat is already text.

---

# 21. VAD / silence removal

Deferred optimization.

Target later:

```text
WAV
→ voice activity detection
→ drop silence
→ chunk speech regions
→ STT
```

Reason:
- participant tracks may contain long silence.
- reduces OpenAI usage.
- reduces upload.
- reduces processing time.

VAD must remain an internal Python optimization and should not change Java's meeting-level contract.

---

# 22. Current status

```text
Capture                              ✅
WAV + manifest                       ✅
1 WAV STT                            ✅
OpenAI provider                      ✅
Provider abstraction                 ✅
Chunking                             ✅
Lazy chunk materialization           ✅
Bounded queue                        ✅
Concurrency=4                        ✅
Retry                                ✅
Progress                             ✅
Manifest-level STT                   ✅
Async job                            ✅
GET status/result                    ✅
Worker cleanup                       ✅
Real async test meeting 12           ✅

Meeting-level multi-manifest         🔄 NEXT
sessionId in result                  🔄 NEXT
WAV failure isolation                🔄 NEXT
Internal key on STT router           🔄 NEXT

VAD                                  ❌ deferred
Java callback                        ❌
Full initial-minutes LLM              ❌
AI Result conclusion                 ✅
```

---

# 23. Next work

Immediate:

1. Implement meeting-level `manifestPaths[]`.
2. Preserve existing single-segment and single-manifest debug endpoints.
3. Add `sessionId` into transcript/failure.
4. Aggregate all manifest chunks into one queue.
5. Maintain global concurrency = 4.
6. Isolate WAV missing/corrupt failures.
7. Compute progress across entire meeting.
8. Apply internal API-key dependency.
9. Test 2+ manifests.

After that:
- Java → Python async job integration.
- Progress/completion integration.
- Transcript persistence.
- Voice + Chat.
- LLM.

---

# 24. Codex update template

After modifying this repository, append/update:

```text
## Change log

### YYYY-MM-DD — <task>

Files added:
- ...

Files modified:
- ...

Endpoints:
- ...

Request/response contract:
- ...

Algorithm:
- ...

Concurrency/retry/progress:
- ...

Tests:
- ...

Known limitation:
- ...

Next:
- ...
```

When changing STT behavior, always state whether the task changed:
- chunk size
- concurrency
- retry policy
- progress calculation
- job lifecycle
- Java-facing API contract

Do not document unrelated FE/Java internals.

---

## Change log

### 2026-08-26 - STT progress callback sender

Files added:
- `app/services/stt_progress_callback_client.py`
- `tests/test_stt_progress_callback_client.py`

Files modified:
- `app/core/config.py`
- `app/models/transcription.py`
- `app/services/transcription_job_service.py`
- `pyproject.toml`
- `tests/test_transcription_job_service.py`
- `PROJECT_CONTEXT.md`

Callback Java URL:
- `POST {MEETING_SERVICE_BASE_URL}/internal/api/minutes/stt/jobs/{processingId}/progress`

Payload:
- `meetingId`
- `processedChunks`
- `totalChunks`
- `progressPercent`

Internal API key:
- Uses existing `settings.internal_api_key`.
- Sends header `X-Internal-Api-Key`.
- Does not create a new auth mechanism.

Callback throttle:
- Controlled by `STT_PROGRESS_CALLBACK_MIN_PERCENT_STEP`.
- Always sends the first callback.
- Always sends terminal `100%`.

Best-effort/non-blocking behavior:
- `TranscriptionJobService` updates local progress first.
- HTTP callback is scheduled as a background task.
- STT does not await Java callback on the critical path.
- Java unavailable, 401, 500, timeout, or connection errors do not fail Python STT.

Done:
- Python progress callback sender completed.
- Runtime `httpx==0.28.1` dependency added.
- Callback task cleanup and `AsyncClient.close()` added to job shutdown.

Todo:
- No completion callback yet.
- No result callback yet.
- No transcript persistence callback yet.
- STT `100%` remains only a progress callback, not Java completion.

### 2026-08-26 - STT completion callback sender

Files added:
- `app/services/stt_completion_callback_client.py`
- `tests/test_stt_completion_callback_client.py`

Files modified:
- `app/core/config.py`
- `app/models/transcription.py`
- `app/services/transcription_job_service.py`
- `tests/test_transcription_job_service.py`
- `PROJECT_CONTEXT.md`

Callback Java URL:
- `POST {MEETING_SERVICE_BASE_URL}/internal/api/minutes/stt/jobs/{processingId}/completion`

Payload:
- `meetingId`
- `status`
- `totalManifests`
- `totalSegments`
- `totalChunks`
- `processedChunks`
- `succeededChunks`
- `failedChunks`
- `errorMessage`

Terminal behavior:
- Sends `COMPLETED`, `PARTIAL`, or `FAILED`.
- `COMPLETED` uses response aggregate counts and `errorMessage=None`.
- `PARTIAL` uses response aggregate counts and a short failed-chunk summary.
- `FAILED` uses response counts when available, or conservative request/current-job counts for unhandled exceptions.
- Completion callback contains no transcripts, failures array, or result object.

Ordering:
- Python stores `job.result`.
- Python sets local terminal status/stage.
- Python then schedules completion callback.
- Java can safely GET Python result after receiving completion.

Retry policy:
- Retries `404`, `408`, `425`, `429`, `5xx`, timeout, and network transport errors.
- `404` is retried to tolerate processingId persistence race on Java side.
- Does not retry permanent `400`, `401`, `403`, `409`.
- Uses bounded attempts from `STT_COMPLETION_CALLBACK_MAX_ATTEMPTS`.
- Uses exponential backoff from `STT_COMPLETION_CALLBACK_RETRY_BASE_SECONDS`.

Failure behavior:
- Completion callback failure does not change Python local terminal state.
- Retry exhausted is logged by the safe wrapper.
- Progress callback behavior is unchanged.

Done:
- Python completion sender added.
- Result-before-callback ordering tested.
- Completion client retry/no-retry behavior tested.

Todo:
- Test Python completion sender against Java E2E.
- After E2E passes, implement Java GET Python result.
