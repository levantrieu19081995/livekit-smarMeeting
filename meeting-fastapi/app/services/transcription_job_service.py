import asyncio
import logging
import uuid
import math
from dataclasses import dataclass
from functools import lru_cache

from fastapi import HTTPException, status

from app.core.config import Settings, get_settings
from app.core.runtime_limits import get_runtime_limits
from app.models.transcription import (
    StartTranscriptionJobResponse,
    SttCompletionCallbackPayload,
    SttProgressCallbackPayload,
    SttTerminalStatus,
    TranscribeMeetingRequest,
    TranscribeMeetingResponse,
    TranscriptionJobResultResponse,
    TranscriptionJobStatusResponse,
)
from app.services.manifest_transcription_service import ManifestTranscriptionService
from app.services.stt_completion_callback_client import SttCompletionCallbackClient
from app.services.stt_progress_callback_client import SttProgressCallbackClient
from app.services.transcription_job_store import (
    SCHEMA_VERSION,
    PersistedTranscriptionJob,
    TranscriptionJobStore,
    TranscriptionJobStoreError,
)

logger = logging.getLogger(__name__)


@dataclass
class _TranscriptionJob:
    processing_id: str
    meeting_id: int
    status: str
    stage: str
    processed_chunks: int
    total_chunks: int
    progress_percent: float
    error: str | None
    result: TranscribeMeetingResponse | None


@dataclass(frozen=True)
class _RequestIdentity:
    request_id: str
    meeting_id: int
    manifest_paths: tuple[str, ...]


class TranscriptionJobService:
    def __init__(
        self,
        manifest_transcription_service: ManifestTranscriptionService,
        progress_callback_client: SttProgressCallbackClient | None = None,
        completion_callback_client: SttCompletionCallbackClient | None = None,
        store: TranscriptionJobStore | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.manifest_transcription_service = manifest_transcription_service
        self.settings = settings or get_settings()
        self._progress_callback_client = progress_callback_client or SttProgressCallbackClient(self.settings)
        self._completion_callback_client = completion_callback_client or SttCompletionCallbackClient(self.settings)
        self._store = store or TranscriptionJobStore(self.settings.capture_root_dir / "_stt_jobs")
        self._jobs: dict[str, _TranscriptionJob] = {}
        self._tasks: dict[str, asyncio.Task] = {}
        self._request_processing_ids: dict[str, str] = {}
        self._request_identities: dict[str, _RequestIdentity] = {}
        self._callback_tasks: set[asyncio.Task] = set()
        self._progress_tasks: dict[str, asyncio.Task] = {}
        self._latest_progress: dict[str, SttProgressCallbackPayload] = {}
        self._lock = asyncio.Lock()
        self._started = False
        self._shutting_down = False

    async def _store_call(self, function, *args):
        # Cancellation cannot interrupt a filesystem thread. Drain it before
        # releasing the service lock or writing another state for the same job.
        task = asyncio.create_task(asyncio.to_thread(function, *args))
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            await task
            raise

    async def startup(self) -> None:
        async with self._lock:
            try:
                await self._startup_locked()
            except TranscriptionJobStoreError as exc:
                logger.error("STT job store startup failed: %s", exc)
                raise

    async def _startup_locked(self) -> None:
        if self._started:
            return

        persisted_jobs = await self._store_call(self._store.rebuild_indexes, self.settings.STT_MAX_PENDING_JOBS)
        jobs: dict[str, _TranscriptionJob] = {}
        request_processing_ids: dict[str, str] = {}
        request_identities: dict[str, _RequestIdentity] = {}
        requests_to_resume: list[tuple[str, TranscribeMeetingRequest]] = []

        for persisted in persisted_jobs:
            identity = _RequestIdentity(
                request_id=persisted.request_id,
                meeting_id=persisted.meeting_id,
                manifest_paths=persisted.manifest_paths,
            )
            if persisted.processing_id in jobs:
                raise TranscriptionJobStoreError(
                    f"Duplicate STT processingId records: {persisted.processing_id}"
                )
            existing_processing_id = request_processing_ids.get(persisted.request_id)
            if existing_processing_id is not None:
                if request_identities[persisted.request_id] != identity:
                    raise TranscriptionJobStoreError(
                        f"Conflicting STT requestId records: {persisted.request_id}"
                    )
                raise TranscriptionJobStoreError(
                    f"Duplicate STT requestId records: {persisted.request_id}"
                )

            jobs[persisted.processing_id] = self._job_from_persisted(persisted)
            request_processing_ids[persisted.request_id] = persisted.processing_id
            request_identities[persisted.request_id] = identity
            if persisted.status in {"QUEUED", "PROCESSING"}:
                requests_to_resume.append((
                    persisted.processing_id,
                    self._request_from_identity(identity),
                ))

        self._jobs = jobs
        self._request_processing_ids = request_processing_ids
        self._request_identities = request_identities
        for processing_id, request in requests_to_resume:
            self._ensure_task_locked(processing_id, request)
        self._started = True

    async def start_job(self, request: TranscribeMeetingRequest) -> StartTranscriptionJobResponse:
        identity = self._identity_for_request(request)
        async with self._lock:
            if self._shutting_down:
                raise HTTPException(503, detail="STT service is shutting down")
            await self._startup_locked()
            existing_processing_id = self._request_processing_ids.get(request.requestId)
            existing_identity = self._request_identities.get(request.requestId)
            if existing_processing_id is None:
                persisted = await self._store_call(self._store.find_request, request.requestId)
                if persisted is not None:
                    existing_processing_id = persisted.processing_id
                    restored_identity = _RequestIdentity(persisted.request_id, persisted.meeting_id, persisted.manifest_paths)
                    existing_identity = restored_identity
                    self._cache_job(self._job_from_persisted(persisted), restored_identity)
            if existing_processing_id is not None:
                if existing_identity != identity:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="requestId already exists with different payload",
                    )
                existing_job = self._jobs.get(existing_processing_id)
                if existing_job is not None and existing_job.status in {"QUEUED", "PROCESSING"}:
                    self._ensure_task_locked(existing_processing_id, request)
                return StartTranscriptionJobResponse(
                    processingId=existing_processing_id,
                    meetingId=request.meetingId,
                    status="QUEUED",
                )

            if len(self._tasks) >= self.settings.STT_MAX_PENDING_JOBS or len(self._callback_tasks) >= self.settings.STT_MAX_CALLBACK_TASKS:
                raise HTTPException(429, detail="STT job queue is full", headers={"Retry-After": "5"})

            processing_id = str(uuid.uuid4())
            job = _TranscriptionJob(
                processing_id=processing_id,
                meeting_id=request.meetingId,
                status="QUEUED",
                stage="QUEUED",
                processed_chunks=0,
                total_chunks=0,
                progress_percent=0,
                error=None,
                result=None,
            )
            await self._store_call(self._store.save, self._persisted_from_job(identity, job))
            self._jobs[processing_id] = job
            self._request_processing_ids[request.requestId] = processing_id
            self._request_identities[request.requestId] = identity
            self._ensure_task_locked(processing_id, request)
        return StartTranscriptionJobResponse(
            processingId=processing_id,
            meetingId=request.meetingId,
            status="QUEUED",
        )

    async def _run_job(self, processing_id: str, request: TranscribeMeetingRequest) -> None:
        try:
            async with get_runtime_limits(self.settings).jobs:
                await self._execute_job(processing_id, request)
        finally:
            async with self._lock:
                self._tasks.pop(processing_id, None)
                self._prune_terminal_cache()

    async def _execute_job(self, processing_id: str, request: TranscribeMeetingRequest) -> None:
        identity = self._identity_for_request(request)
        try:
            async with self._lock:
                job = self._jobs[processing_id]
                job.status = "PROCESSING"
                job.stage = "TRANSCRIBING"
                await self._store_call(self._store.save, self._persisted_from_job(identity, job))
                last_reported_progress_percent: float | None = (
                    job.progress_percent
                    if job.progress_percent > 0 or job.processed_chunks > 0
                    else None
                )

            async def on_progress(processed: int, total: int, percent: float) -> None:
                nonlocal last_reported_progress_percent
                rounded_percent = round(percent, 2)
                if processed < 0 or total < processed or total > self.settings.STT_MAX_CHUNKS_PER_JOB or not math.isfinite(percent) or not 0 <= percent <= 100:
                    raise ValueError("Invalid STT progress")
                async with self._lock:
                    job = self._jobs[processing_id]
                    effective_total = max(job.total_chunks, total)
                    effective_processed = min(
                        max(job.processed_chunks, processed),
                        effective_total,
                    )
                    effective_percent = max(job.progress_percent, rounded_percent)
                    job.processed_chunks = effective_processed
                    job.total_chunks = effective_total
                    job.progress_percent = effective_percent
                    await self._store_call(self._store.save, self._persisted_from_job(identity, job))

                if not self.settings.STT_PROGRESS_CALLBACK_ENABLED:
                    return

                should_send = (
                    last_reported_progress_percent is None
                    or effective_percent >= 100
                    or effective_percent - last_reported_progress_percent
                    >= self.settings.STT_PROGRESS_CALLBACK_MIN_PERCENT_STEP
                )
                if not should_send:
                    return

                last_reported_progress_percent = effective_percent
                payload = SttProgressCallbackPayload(
                    meetingId=request.meetingId,
                    processedChunks=effective_processed,
                    totalChunks=effective_total,
                    progressPercent=effective_percent,
                )
                self._schedule_progress_callback(processing_id, payload)

            async with asyncio.timeout(self.settings.STT_JOB_TIMEOUT_SECONDS):
                result = await self.manifest_transcription_service.transcribe_meeting(request, progress_callback=on_progress)
            async with self._lock:
                job = self._jobs[processing_id]
                if result.status == "COMPLETED":
                    job.status = "COMPLETED"
                    job.stage = "COMPLETED"
                elif result.status == "PARTIAL":
                    job.status = "PARTIAL"
                    job.stage = "COMPLETED"
                else:
                    job.status = "FAILED"
                    job.stage = "FAILED"
                job.processed_chunks = result.processedChunks
                job.total_chunks = result.totalChunks
                job.progress_percent = 100
                job.result = result
                await self._store_call(self._store.save, self._persisted_from_job(identity, job))
                payload = self._completion_payload_from_result(result, None)
                job.result = None
            self._schedule_completion_callback(processing_id, payload)
        except asyncio.CancelledError:
            async with self._lock:
                if self._shutting_down:
                    if processing_id in self._jobs: self._jobs[processing_id].result = None
                    raise
                if processing_id in self._jobs:
                    job = self._jobs[processing_id]
                    job.status = "FAILED"
                    job.stage = "FAILED"
                    job.error = "Transcription job cancelled"
                    job.result = None
                    await self._store_call(self._store.save, self._persisted_from_job(identity, job))
                    payload = self._completion_payload_from_exception(request, job, job.error)
                else:
                    payload = self._completion_payload_from_exception(request, None, "Transcription job cancelled")
            self._schedule_completion_callback(processing_id, payload)
            raise
        except Exception as exc:
            detail = exc.detail if isinstance(exc, HTTPException) else "Transcription processing failed"
            async with self._lock:
                if processing_id in self._jobs:
                    job = self._jobs[processing_id]
                    job.status = "FAILED"
                    job.stage = "FAILED"
                    job.error = str(detail)
                    job.result = None
                    try:
                        await self._store_call(self._store.save, self._persisted_from_job(identity, job))
                    except Exception:
                        logger.error("STT failure receipt could not be persisted processingId=%s", processing_id)
                    payload = self._completion_payload_from_exception(request, job, str(detail))
                else:
                    payload = self._completion_payload_from_exception(request, None, str(detail))
            self._schedule_completion_callback(processing_id, payload)

    async def get_status(self, processing_id: str) -> TranscriptionJobStatusResponse:
        async with self._lock:
            job = await self._get_job_locked(processing_id)
            if job is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transcription job not found")
            return TranscriptionJobStatusResponse(
                processingId=job.processing_id,
                meetingId=job.meeting_id,
                status=job.status,
                stage=job.stage,
                processedChunks=job.processed_chunks,
                totalChunks=job.total_chunks,
                progressPercent=job.progress_percent,
                error=job.error,
            )

    async def get_result(self, processing_id: str) -> TranscriptionJobResultResponse:
        async with self._lock:
            job = await self._get_job_locked(processing_id)
            if job is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transcription job not found")
            if job.status in {"QUEUED", "PROCESSING"}:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Transcription job is not completed")
            persisted = await self._store_call(self._store.load, processing_id)
            result = persisted.result if persisted is not None else None
            if job.status == "FAILED" and result is None:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Transcription job failed")
            if result is None:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Transcription job is not completed")
            return TranscriptionJobResultResponse(processingId=job.processing_id, result=result)

    async def _get_job_locked(self, processing_id: str) -> _TranscriptionJob | None:
        job = self._jobs.get(processing_id)
        if job is not None: return job
        persisted = await self._store_call(self._store.metadata, processing_id)
        if persisted is None: return None
        job = self._job_from_persisted(persisted)
        self._cache_job(job, _RequestIdentity(persisted.request_id, persisted.meeting_id, persisted.manifest_paths))
        return job

    def _cache_job(self, job: _TranscriptionJob, identity: _RequestIdentity) -> None:
        self._jobs[job.processing_id] = job
        self._request_processing_ids[identity.request_id] = job.processing_id
        self._request_identities[identity.request_id] = identity
        self._prune_terminal_cache()

    def _prune_terminal_cache(self) -> None:
        terminal = [key for key, job in self._jobs.items() if job.status in {"COMPLETED", "PARTIAL", "FAILED"} and key not in self._tasks]
        for processing_id in terminal[:max(0, len(terminal) - self.settings.STT_TERMINAL_CACHE_SIZE)]:
            self._jobs.pop(processing_id, None)
            request_ids = [request_id for request_id, pid in self._request_processing_ids.items() if pid == processing_id]
            for request_id in request_ids:
                self._request_processing_ids.pop(request_id, None)
                self._request_identities.pop(request_id, None)

    def _schedule_progress_callback(
        self,
        processing_id: str,
        payload: SttProgressCallbackPayload,
    ) -> None:
        self._latest_progress[processing_id] = payload
        current = self._progress_tasks.get(processing_id)
        if current is not None and not current.done(): return
        if len(self._callback_tasks) >= self.settings.STT_MAX_CALLBACK_TASKS:
            self._latest_progress.pop(processing_id, None)
            return
        async def drain():
            try:
                while processing_id in self._latest_progress:
                    latest = self._latest_progress.pop(processing_id)
                    await self._send_progress_callback_safe(processing_id, latest)
            finally:
                self._progress_tasks.pop(processing_id, None)
        task = asyncio.create_task(
            drain()
        )
        self._progress_tasks[processing_id] = task
        self._callback_tasks.add(task)
        task.add_done_callback(self._callback_tasks.discard)

    def _schedule_completion_callback(
        self,
        processing_id: str,
        payload: SttCompletionCallbackPayload,
    ) -> None:
        if not self.settings.STT_COMPLETION_CALLBACK_ENABLED:
            return
        if len(self._callback_tasks) >= self.settings.STT_MAX_CALLBACK_TASKS:
            logger.warning("STT callback queue full; terminal result remains available processingId=%s", processing_id)
            return
        task = asyncio.create_task(
            self._send_completion_callback_safe(processing_id, payload)
        )
        self._callback_tasks.add(task)
        task.add_done_callback(self._callback_tasks.discard)

    async def _send_progress_callback_safe(
        self,
        processing_id: str,
        payload: SttProgressCallbackPayload,
    ) -> None:
        try:
            await self._progress_callback_client.send_progress(processing_id, payload)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning(
                "STT progress callback failed processingId=%s meetingId=%s progressPercent=%s error=%s",
                processing_id,
                payload.meetingId,
                payload.progressPercent,
                type(exc).__name__,
            )

    async def _send_completion_callback_safe(
        self,
        processing_id: str,
        payload: SttCompletionCallbackPayload,
    ) -> None:
        try:
            await self._completion_callback_client.send_completion(processing_id, payload)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning(
                "STT completion callback failed processingId=%s meetingId=%s status=%s error=%s",
                processing_id,
                payload.meetingId,
                payload.status.value,
                type(exc).__name__,
            )

    def _completion_payload_from_result(
        self,
        result: TranscribeMeetingResponse,
        error_message: str | None,
    ) -> SttCompletionCallbackPayload:
        status_value = SttTerminalStatus(result.status)
        return SttCompletionCallbackPayload(
            meetingId=result.meetingId,
            status=status_value,
            totalManifests=result.totalManifests,
            totalSegments=result.totalSegments,
            totalChunks=result.totalChunks,
            processedChunks=result.processedChunks,
            succeededChunks=result.succeededChunks,
            failedChunks=result.failedChunks,
            errorMessage=self._result_error_message(result, error_message),
        )

    def _completion_payload_from_exception(
        self,
        request: TranscribeMeetingRequest,
        job: _TranscriptionJob | None,
        error_message: str | None,
    ) -> SttCompletionCallbackPayload:
        return SttCompletionCallbackPayload(
            meetingId=request.meetingId,
            status=SttTerminalStatus.FAILED,
            totalManifests=len(request.manifestPaths),
            totalSegments=0,
            totalChunks=job.total_chunks if job else 0,
            processedChunks=job.processed_chunks if job else 0,
            succeededChunks=0,
            failedChunks=0,
            errorMessage=self._safe_error_message(error_message),
        )

    def _result_error_message(
        self,
        result: TranscribeMeetingResponse,
        error_message: str | None,
    ) -> str | None:
        if error_message:
            return self._safe_error_message(error_message)
        if result.status == "COMPLETED":
            return None
        if result.status == "PARTIAL":
            return self._safe_error_message(f"{result.failedChunks} STT chunk failed" if result.failedChunks == 1 else f"{result.failedChunks} STT chunks failed")
        if result.failures:
            return self._safe_error_message(result.failures[0].error)
        return self._safe_error_message("STT job failed")

    def _safe_error_message(self, value: str | None) -> str | None:
        if not value:
            return None
        message = str(value).strip()
        if not message:
            return None
        if self.settings.internal_api_key:
            message = message.replace(self.settings.internal_api_key, "[redacted]")
        if self.settings.OPENAI_API_KEY:
            message = message.replace(self.settings.OPENAI_API_KEY, "[redacted]")
        return message[:2000]

    async def shutdown(self) -> None:
        async with self._lock:
            self._shutting_down = True
            tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        async with self._lock:
            self._tasks.clear()

        callback_tasks = list(self._callback_tasks)
        for task in callback_tasks:
            task.cancel()
        if callback_tasks:
            await asyncio.gather(*callback_tasks, return_exceptions=True)
        self._callback_tasks.clear()
        self._progress_tasks.clear()
        self._latest_progress.clear()
        close = getattr(self.manifest_transcription_service, "close", None)
        closing = [self._progress_callback_client.close(), self._completion_callback_client.close()]
        if close is not None: closing.append(close())
        outcomes = await asyncio.gather(*closing, return_exceptions=True)
        for outcome in outcomes:
            if isinstance(outcome, BaseException): raise outcome

    def _ensure_task_locked(
        self,
        processing_id: str,
        request: TranscribeMeetingRequest,
    ) -> None:
        task = self._tasks.get(processing_id)
        if task is not None and not task.done():
            return
        self._tasks[processing_id] = asyncio.create_task(
            self._run_job(processing_id, request)
        )

    def _identity_for_request(self, request: TranscribeMeetingRequest) -> _RequestIdentity:
        return _RequestIdentity(
            request_id=request.requestId,
            meeting_id=request.meetingId,
            manifest_paths=tuple(request.manifestPaths),
        )

    def _request_from_identity(self, identity: _RequestIdentity) -> TranscribeMeetingRequest:
        return TranscribeMeetingRequest(
            requestId=identity.request_id,
            meetingId=identity.meeting_id,
            manifestPaths=list(identity.manifest_paths),
        )

    def _persisted_from_job(
        self,
        identity: _RequestIdentity,
        job: _TranscriptionJob,
    ) -> PersistedTranscriptionJob:
        return PersistedTranscriptionJob(
            schema_version=SCHEMA_VERSION,
            request_id=identity.request_id,
            processing_id=job.processing_id,
            meeting_id=job.meeting_id,
            manifest_paths=identity.manifest_paths,
            status=job.status,
            stage=job.stage,
            processed_chunks=job.processed_chunks,
            total_chunks=job.total_chunks,
            progress_percent=job.progress_percent,
            error=job.error,
            result=job.result,
        )

    def _job_from_persisted(self, persisted: PersistedTranscriptionJob) -> _TranscriptionJob:
        return _TranscriptionJob(
            processing_id=persisted.processing_id,
            meeting_id=persisted.meeting_id,
            status=persisted.status,
            stage=persisted.stage,
            processed_chunks=persisted.processed_chunks,
            total_chunks=persisted.total_chunks,
            progress_percent=persisted.progress_percent,
            error=persisted.error,
            result=persisted.result,
        )


@lru_cache
def get_transcription_job_service() -> TranscriptionJobService:
    return TranscriptionJobService(ManifestTranscriptionService())
