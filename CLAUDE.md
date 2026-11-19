# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Layout

Monorepo of three independently-run services for a LiveKit-based "Smart Meeting" system (video meetings + audio capture + STT + AI-generated minutes/conclusions). The repo root also holds Vietnamese-language specs (`CUSC_SMART_MEETING_UI_SPEC.md`, `LIVEKIT_SYSTEM_BASELINE_PHASE3_FROZEN.md`, `SMART_MEETING_BACKLOG.md`) and PowerShell smoke/E2E scripts.

| Dir | Stack | Port | Role |
|---|---|---|---|
| `meeting-frontend` | Next.js / React / TS (originally a fork of `livekit-examples/meet`) | 3000 | UI + LiveKit conference |
| `meeting-service` | Spring Boot 3.4 / Java 17 / PostgreSQL + Flyway | 8080 | Business backend, **source of truth** |
| `meeting-fastapi` | FastAPI / Python ≥3.12 | 8000 | LiveKit audio capture + OpenAI STT + AI conclusions |

LiveKit server itself is **not** in this repo: it runs on another machine (default `ws://172.16.20.177:7880`, set via `LIVEKIT_URL` in `meeting-fastapi/.env` and the `livekit` block of `meeting-service/.../application-dev.yml`; `meeting-fastapi/.env.example` defaults to `ws://127.0.0.1:7880`).

## Architecture (the part spanning multiple files)

```
Frontend ──► Java (8080) ──► FastAPI (8000) ──► OpenAI
   │            ▲   ▲            │
   │            │   └── internal callbacks (STT progress/completion) ◄┘
   └── LiveKit (room media; join token comes from Java's join-meeting response)
```

- **The frontend never calls FastAPI** for minutes/STT. It talks to Java via `meeting-frontend/services/management/*` (`httpClient.ts` handles session cookie + CSRF). Requests go to the same-origin prefix `/meeting-service/*`, which `next.config.js` rewrites to `MEETING_SERVICE_INTERNAL_URL` (default `http://127.0.0.1:8080`) — there are no per-endpoint Next route handlers for this (`app/api/` only has `help-files`; the `connection-details`/`record`/`management` dirs there are empty leftovers from the upstream fork). Java issues the LiveKit join token (`MeetingController` → `LiveKitServiceImpl`).
- **Java owns all state**: auth/sessions (cookie, JDBC-backed), meetings, participants, points, chat persistence, documents, polls, Q&A, speaking history, minutes versions, job state, and the "one successful AI conclusion" limit. Python is stateless w.r.t. business data.
- **Python's two jobs**: (1) *Capture* – LiveKit audio → per-participant WAV → `manifest.json`; (2) *Post-processing* – manifests → chunk → STT → transcript, reporting back to Java via internal callbacks (`stt_progress_callback_client.py`, `stt_completion_callback_client.py` → Java's `InternalStt*CallbackController`). AI conclusions (`/api/ai/conclusions/generate`) use OpenAI structured outputs with disk receipts (UUID + context hash) for idempotency.
- **Java ↔ Python auth** is a shared `X-Internal-Api-Key` (`AI_INTERNAL_API_KEY` in Java, `INTERNAL_API_KEY` in Python). The conclusions route always requires it; capture/STT routes require it when `REQUIRE_INTERNAL_API_KEY` is on.
- Java layering: `controller` → `service`/`service/impl` → `repository`/`entity`, with `client/{ai,capture,conclusion,stt}` as the HTTP clients to FastAPI. Schema is managed by Flyway (`src/main/resources/db/migration/V*.sql`) and Hibernate runs with `ddl-auto: validate` — schema changes need a new migration.
- Meeting modes include ordinary scheduled meetings and `POINT` meetings (multiple named agenda points; join requires a `pointId`; points are immutable after the first join).

## Commands

Run everything (opens one PowerShell window per service, starts FastAPI → Java → Frontend, waits on ports):
```powershell
.\dev.ps1 start | stop | status | restart
```
Prerequisites: PostgreSQL reachable at the URL in `application-dev.yml` (`127.0.0.1:5432/meeting_db`; `meeting-service/docker-compose.dev.yml` offers one on 5433), Maven, JDK 17, Node 20, Python venv at `meeting-fastapi/.venv` (falls back to global `py`).

### meeting-frontend
```bash
npm run dev            # next dev
npm run build
npm run lint           # next lint (lint:fix to autofix)
npm test               # vitest run (all)
npx vitest run services/management/httpClient.test.ts   # single file
npx vitest run -t "name"                                  # single test by name
npm run format:check   # prettier (format:write to apply)
```
Tests are colocated (`*.test.ts` beside the source in `lib/` and `services/management/`).

### meeting-service
```bash
mvn spring-boot:run
mvn clean test
mvn test -Dtest=ClassName            # single class
mvn test -Dtest=ClassName#method     # single method
mvn -Dmaven.repo.local=.m2 clean test   # use project-local Maven repo
```
Its README says not to run `mvn package`/`install` unless asked. Default Spring profile is `dev` (`SPRING_PROFILES_ACTIVE`).

### meeting-fastapi
```bash
python -m pip install -e ".[test]"
.\run.ps1                                   # uvicorn app.main:app --reload on :8000
pytest                                      # asyncio_mode=auto, testpaths=tests
pytest tests/test_conclusion_service.py::test_name   # single test
```
Config comes from `meeting-fastapi/.env` (see `.env.example`: `LIVEKIT_*`, `OPENAI_API_KEY`, `STT_MODEL`, `CAPTURE_*`, `INTERNAL_API_KEY`).

## Conventions worth knowing

- Each service has a `PROJECT_CONTEXT.md` (1000–1700 lines: API contracts, algorithms, change log, known constraints). They state that **after every modifying task the relevant sections (`Current implementation`, `Change log`, etc.) must be updated** — do so, touching only affected sections. Source code wins if they disagree. Read the relevant one before non-trivial work in that service.
- Secrets live in untracked `meeting-fastapi/.env` and `meeting-frontend/.env.local`; never commit them. Large/generated dirs (`Version/`, `output/`, `tmp/`, `target/`, `.next*`, `meeting-fastapi/data/`, `.m2/`) are git-ignored — `output/` holds verification evidence from earlier runs.
- `meeting-frontend/.git.upstream-backup/` is the original upstream clone's git dir (renamed so this repo can track the folder); don't treat it as live.
- Documentation, comments and some UI strings are in Vietnamese.
