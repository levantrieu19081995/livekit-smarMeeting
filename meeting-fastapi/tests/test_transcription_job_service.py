import asyncio
import shutil
import uuid
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.core.config import Settings
from app.models.transcription import (
    FailedTranscriptChunk,
    SttCompletionCallbackPayload,
    SttProgressCallbackPayload,
    SttTerminalStatus,
    TranscribeMeetingRequest,
    TranscribeMeetingResponse,
    TranscriptChunkResult,
)
from app.services.transcription_job_service import TranscriptionJobService
from app.services.transcription_job_store import (
    SCHEMA_VERSION,
    PersistedTranscriptionJob,
    TranscriptionJobStore,
)


@pytest.fixture
def tmp_path() -> Path:
    path = Path.cwd() / "test_workspace_tmp" / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def request() -> TranscribeMeetingRequest:
    return TranscribeMeetingRequest(
        requestId="minutes-job-15",
        meetingId=12,
        manifestPaths=[
            "meeting-12/session-a/manifest.json",
            "meeting-12/session-b/manifest.json",
        ],
    )


def meeting_response(
    status: str = "COMPLETED",
    *,
    total_manifests: int = 2,
    total_segments: int = 1,
    total_chunks: int | None = None,
    processed_chunks: int | None = None,
    succeeded_chunks: int | None = None,
    failed_chunks: int | None = None,
) -> TranscribeMeetingResponse:
    succeeded = (0 if status == "FAILED" else 1) if succeeded_chunks is None else succeeded_chunks
    failed = (1 if status in {"FAILED", "PARTIAL"} else 0) if failed_chunks is None else failed_chunks
    total = succeeded + failed if total_chunks is None else total_chunks
    processed = total if processed_chunks is None else processed_chunks
    return TranscribeMeetingResponse(
        meetingId=12,
        sessionIds=["session-a", "session-b"],
        status=status,
        totalManifests=total_manifests,
        totalSegments=total_segments,
        totalChunks=total,
        processedChunks=processed,
        succeededChunks=succeeded,
        failedChunks=failed,
        transcripts=[
            TranscriptChunkResult(
                sessionId="session-a",
                attendeeId="attendee-1",
                speakerName="Speaker",
                segmentId="segment-1",
                segmentIndex=1,
                chunkIndex=1,
                chunkStartMs=0,
                chunkDurationMs=1000,
                segmentStartedAt=None,
                absoluteStartTime=None,
                filePath="participants/a1/segment-1.wav",
                text="hello",
            )
        ]
        if succeeded
        else [],
        failures=[
            FailedTranscriptChunk(
                sessionId="session-a",
                segmentId="segment-failed",
                chunkIndex=1,
                filePath="participants/a1/failed.wav",
                attempts=3,
                error="provider failed",
            )
        ]
        if failed
        else [],
    )


class FakeManifestTranscriptionService:
    def __init__(
        self,
        *,
        result: TranscribeMeetingResponse | None = None,
        delay: float = 0,
        error: Exception | None = None,
        progress: list[tuple[int, int, float]] | None = None,
        block_after_progress: bool = False,
        never_finish: bool = False,
    ) -> None:
        self.result = result or meeting_response()
        self.delay = delay
        self.error = error
        self.progress = progress or []
        self.block_after_progress = block_after_progress
        self.never_finish = never_finish
        self.progress_done = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = False
        self.calls = 0

    async def transcribe_meeting(self, request, progress_callback=None):
        try:
            self.calls += 1
            if self.never_finish:
                await asyncio.Event().wait()
            if self.delay:
                await asyncio.sleep(self.delay)
            for processed, total, percent in self.progress:
                if progress_callback is not None:
                    await progress_callback(processed, total, percent)
                await asyncio.sleep(0)
            if self.progress:
                self.progress_done.set()
            if self.block_after_progress:
                await self.release.wait()
            if self.error:
                raise self.error
            return self.result
        except asyncio.CancelledError:
            self.cancelled = True
            raise


class FakeProgressCallbackClient:
    def __init__(self, *, error: Exception | None = None, block: bool = False) -> None:
        self.error = error
        self.block = block
        self.calls: list[tuple[str, SttProgressCallbackPayload]] = []
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = False
        self.closed = False

    async def send_progress(self, processing_id: str, payload: SttProgressCallbackPayload) -> None:
        self.started.set()
        self.calls.append((processing_id, payload))
        try:
            if self.block:
                await self.release.wait()
            if self.error:
                raise self.error
        except asyncio.CancelledError:
            self.cancelled = True
            raise

    async def close(self) -> None:
        self.closed = True


class FakeCompletionCallbackClient:
    def __init__(
        self,
        *,
        error: Exception | None = None,
        block: bool = False,
        job_service: TranscriptionJobService | None = None,
        store_root: Path | None = None,
    ) -> None:
        self.error = error
        self.block = block
        self.job_service = job_service
        self.store_root = store_root
        self.calls: list[tuple[str, SttCompletionCallbackPayload]] = []
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = False
        self.closed = False
        self.result_ready_when_called = False
        self.status_when_called: str | None = None
        self.durable_result_ready_when_called = False
        self.durable_status_when_called: str | None = None

    async def send_completion(self, processing_id: str, payload: SttCompletionCallbackPayload) -> None:
        if self.job_service is not None:
            status = await self.job_service.get_status(processing_id)
            self.status_when_called = status.status
            result = await self.job_service.get_result(processing_id)
            self.result_ready_when_called = result.result is not None
        if self.store_root is not None:
            persisted = {
                job.processing_id: job
                for job in TranscriptionJobStore(self.store_root).load_all()
            }[processing_id]
            self.durable_status_when_called = persisted.status
            self.durable_result_ready_when_called = persisted.result is not None
        self.started.set()
        self.calls.append((processing_id, payload))
        try:
            if self.block:
                await self.release.wait()
            if self.error:
                raise self.error
        except asyncio.CancelledError:
            self.cancelled = True
            raise

    async def close(self) -> None:
        self.closed = True


def settings(
    *,
    callback_enabled: bool = True,
    min_percent_step: float = 5.0,
    completion_callback_enabled: bool = True,
    capture_root_dir: Path | None = None,
) -> Settings:
    fallback_temp_root = Path.cwd() / "test_workspace_tmp"
    fallback_temp_root.mkdir(parents=True, exist_ok=True)
    fallback_capture_root = fallback_temp_root / uuid.uuid4().hex
    fallback_capture_root.mkdir(parents=True, exist_ok=True)
    return Settings(
        capture_root_dir=capture_root_dir or fallback_capture_root,
        STT_PROGRESS_CALLBACK_ENABLED=callback_enabled,
        STT_PROGRESS_CALLBACK_MIN_PERCENT_STEP=min_percent_step,
        STT_COMPLETION_CALLBACK_ENABLED=completion_callback_enabled,
    )


def service(
    manifest_service: FakeManifestTranscriptionService,
    callback_client: FakeProgressCallbackClient | None = None,
    completion_callback_client: FakeCompletionCallbackClient | None = None,
    callback_enabled: bool = True,
    min_percent_step: float = 5.0,
    completion_callback_enabled: bool = True,
    store_root: Path | None = None,
) -> TranscriptionJobService:
    capture_root_dir = store_root.parent if store_root is not None else None
    return TranscriptionJobService(
        manifest_service,
        progress_callback_client=callback_client or FakeProgressCallbackClient(),
        completion_callback_client=completion_callback_client or FakeCompletionCallbackClient(),
        store=TranscriptionJobStore(store_root) if store_root is not None else None,
        settings=settings(
            callback_enabled=callback_enabled,
            min_percent_step=min_percent_step,
            completion_callback_enabled=completion_callback_enabled,
            capture_root_dir=capture_root_dir,
        ),
    )


async def wait_for_terminal(service: TranscriptionJobService, processing_id: str):
    for _ in range(50):
        status = await service.get_status(processing_id)
        if status.status in {"COMPLETED", "PARTIAL", "FAILED"}:
            return status
        await asyncio.sleep(0.01)
    raise AssertionError("job did not finish")


async def wait_for_callback_count(callback_client, count: int) -> None:
    for _ in range(50):
        if len(callback_client.calls) >= count:
            return
        await asyncio.sleep(0.01)
    raise AssertionError("callback count not reached")


@pytest.mark.asyncio
async def test_start_job_returns_queued_immediately() -> None:
    job_service = service(FakeManifestTranscriptionService(delay=0.05))

    response = await job_service.start_job(request())

    assert response.processingId
    assert response.meetingId == 12
    assert response.status == "QUEUED"
    assert len(job_service._jobs) == 1
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_duplicate_request_id_returns_same_processing_id_without_second_task() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    job_service = service(fake)

    first = await job_service.start_job(request())
    second = await job_service.start_job(request())

    assert second.processingId == first.processingId
    assert len(job_service._jobs) == 1
    assert len(job_service._tasks) == 1
    assert len(job_service._request_processing_ids) == 1
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_concurrent_duplicate_request_id_creates_one_job_and_task() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    job_service = service(fake)

    responses = await asyncio.gather(
        job_service.start_job(request()),
        job_service.start_job(request()),
        job_service.start_job(request()),
    )

    assert len({response.processingId for response in responses}) == 1
    assert len(job_service._jobs) == 1
    assert len(job_service._tasks) == 1
    assert len(job_service._request_processing_ids) == 1
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_same_request_id_different_meeting_id_returns_409() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    job_service = service(fake)
    await job_service.start_job(request())

    with pytest.raises(HTTPException) as exc_info:
        await job_service.start_job(
            TranscribeMeetingRequest(
                requestId="minutes-job-15",
                meetingId=99,
                manifestPaths=[
                    "meeting-12/session-a/manifest.json",
                    "meeting-12/session-b/manifest.json",
                ],
            )
        )

    assert exc_info.value.status_code == 409
    assert len(job_service._jobs) == 1
    assert len(job_service._tasks) == 1
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_same_request_id_different_manifest_paths_returns_409() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    job_service = service(fake)
    await job_service.start_job(request())

    with pytest.raises(HTTPException) as exc_info:
        await job_service.start_job(
            TranscribeMeetingRequest(
                requestId="minutes-job-15",
                meetingId=12,
                manifestPaths=["meeting-12/session-c/manifest.json"],
            )
        )

    assert exc_info.value.status_code == 409
    assert len(job_service._jobs) == 1
    assert len(job_service._tasks) == 1
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_different_request_id_creates_distinct_processing_id_and_job() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    job_service = service(fake)

    first = await job_service.start_job(request())
    second = await job_service.start_job(
        TranscribeMeetingRequest(
            requestId="minutes-job-16",
            meetingId=12,
            manifestPaths=[
                "meeting-12/session-a/manifest.json",
                "meeting-12/session-b/manifest.json",
            ],
        )
    )

    assert second.processingId != first.processingId
    assert len(job_service._jobs) == 2
    assert len(job_service._tasks) == 2
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_job_status_and_result_after_completion() -> None:
    job_service = service(FakeManifestTranscriptionService(delay=0.01))
    started = await job_service.start_job(request())

    initial = await job_service.get_status(started.processingId)
    assert initial.status in {"QUEUED", "PROCESSING"}
    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)

    assert final.status == "COMPLETED"
    assert final.stage == "COMPLETED"
    assert final.progressPercent == 100
    assert result.processingId == started.processingId
    assert result.result.status == "COMPLETED"


@pytest.mark.asyncio
async def test_progress_callback_updates_job_status() -> None:
    fake = FakeManifestTranscriptionService(
        progress=[(1, 4, 25), (2, 4, 50), (3, 4, 75), (4, 4, 100)],
        block_after_progress=True,
    )
    job_service = service(fake)
    started = await job_service.start_job(request())

    await fake.progress_done.wait()
    status = await job_service.get_status(started.processingId)
    fake.release.set()
    await wait_for_terminal(job_service, started.processingId)

    assert status.status == "PROCESSING"
    assert status.stage == "TRANSCRIBING"
    assert status.processedChunks == 4
    assert status.totalChunks == 4
    assert status.progressPercent == 100


@pytest.mark.asyncio
async def test_partial_result_sets_partial_status_and_result_is_available() -> None:
    job_service = service(FakeManifestTranscriptionService(result=meeting_response("PARTIAL")))
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)

    assert final.status == "PARTIAL"
    assert final.stage == "COMPLETED"
    assert result.result.status == "PARTIAL"


@pytest.mark.asyncio
async def test_service_exception_marks_job_failed_with_error() -> None:
    job_service = service(FakeManifestTranscriptionService(error=RuntimeError("provider exploded")))
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)

    assert final.status == "FAILED"
    assert final.stage == "FAILED"
    assert final.error == "Transcription processing failed"


@pytest.mark.asyncio
async def test_get_result_while_processing_returns_409() -> None:
    fake = FakeManifestTranscriptionService(block_after_progress=True, progress=[(1, 4, 25)])
    job_service = service(fake)
    started = await job_service.start_job(request())
    await fake.progress_done.wait()

    with pytest.raises(HTTPException) as exc_info:
        await job_service.get_result(started.processingId)

    assert exc_info.value.status_code == 409
    assert exc_info.value.detail == "Transcription job is not completed"
    fake.release.set()
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_unknown_job_returns_404() -> None:
    job_service = service(FakeManifestTranscriptionService())

    with pytest.raises(HTTPException) as exc_info:
        await job_service.get_status("missing")

    assert exc_info.value.status_code == 404
    assert exc_info.value.detail == "Transcription job not found"


@pytest.mark.asyncio
async def test_shutdown_cancels_running_tasks() -> None:
    fake = FakeManifestTranscriptionService(never_finish=True)
    completion_client = FakeCompletionCallbackClient()
    job_service = service(fake, completion_callback_client=completion_client)
    started = await job_service.start_job(request())
    await asyncio.sleep(0)

    await job_service.shutdown()
    status = await job_service.get_status(started.processingId)

    assert fake.cancelled is True
    assert job_service._tasks == {}
    assert status.status == "PROCESSING"
    assert status.stage == "TRANSCRIBING"
    assert completion_client.calls == []


@pytest.mark.asyncio
async def test_duplicate_request_id_returns_same_processing_id_after_restart(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    fake_a = FakeManifestTranscriptionService(never_finish=True)
    service_a = service(fake_a, store_root=store_root)
    first = await service_a.start_job(request())
    await service_a.shutdown()

    fake_b = FakeManifestTranscriptionService(never_finish=True)
    service_b = service(fake_b, store_root=store_root)
    await service_b.startup()
    second = await service_b.start_job(request())

    assert second.processingId == first.processingId
    assert len(service_b._jobs) == 1
    assert len(service_b._tasks) == 1
    await service_b.shutdown()


@pytest.mark.asyncio
async def test_conflicting_request_id_returns_409_after_restart(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    service_a = service(FakeManifestTranscriptionService(never_finish=True), store_root=store_root)
    first = await service_a.start_job(request())
    await service_a.shutdown()

    service_b = service(FakeManifestTranscriptionService(never_finish=True), store_root=store_root)
    await service_b.startup()

    with pytest.raises(HTTPException) as exc_info:
        await service_b.start_job(
            TranscribeMeetingRequest(
                requestId="minutes-job-15",
                meetingId=99,
                manifestPaths=[
                    "meeting-12/session-a/manifest.json",
                    "meeting-12/session-b/manifest.json",
                ],
            )
        )

    assert exc_info.value.status_code == 409
    assert service_b._request_processing_ids["minutes-job-15"] == first.processingId
    assert len(service_b._jobs) == 1
    assert len(service_b._tasks) == 1
    await service_b.shutdown()


@pytest.mark.asyncio
async def test_terminal_result_is_available_after_restart_without_rerun(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    completion_a = FakeCompletionCallbackClient()
    service_a = service(
        FakeManifestTranscriptionService(result=meeting_response("PARTIAL")),
        completion_callback_client=completion_a,
        store_root=store_root,
    )
    started = await service_a.start_job(request())
    await wait_for_terminal(service_a, started.processingId)
    await wait_for_callback_count(completion_a, 1)

    fake_b = FakeManifestTranscriptionService()
    completion_b = FakeCompletionCallbackClient()
    service_b = service(fake_b, completion_callback_client=completion_b, store_root=store_root)
    await service_b.startup()

    status = await service_b.get_status(started.processingId)
    result = await service_b.get_result(started.processingId)

    assert status.status == "PARTIAL"
    assert result.result.status == "PARTIAL"
    assert fake_b.calls == 0
    assert completion_b.calls == []
    await service_b.shutdown()


@pytest.mark.asyncio
async def test_non_terminal_job_resumes_after_startup(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    TranscriptionJobStore(store_root).save(
        PersistedTranscriptionJob(
            schema_version=SCHEMA_VERSION,
            request_id="minutes-job-15",
            processing_id="processing-15",
            meeting_id=12,
            manifest_paths=tuple(request().manifestPaths),
            status="QUEUED",
            stage="QUEUED",
            processed_chunks=0,
            total_chunks=0,
            progress_percent=0,
            error=None,
            result=None,
        )
    )
    completion_client = FakeCompletionCallbackClient()
    fake = FakeManifestTranscriptionService(result=meeting_response("COMPLETED"))
    job_service = service(fake, completion_callback_client=completion_client, store_root=store_root)

    await job_service.startup()
    final = await wait_for_terminal(job_service, "processing-15")
    await wait_for_callback_count(completion_client, 1)

    assert final.status == "COMPLETED"
    assert fake.calls == 1
    assert completion_client.calls[0][0] == "processing-15"
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_shutdown_owned_cancellation_keeps_durable_non_terminal_for_resume(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    fake_a = FakeManifestTranscriptionService(never_finish=True)
    completion_a = FakeCompletionCallbackClient()
    service_a = service(fake_a, completion_callback_client=completion_a, store_root=store_root)
    started = await service_a.start_job(request())
    await asyncio.sleep(0)

    await service_a.shutdown()
    persisted = TranscriptionJobStore(store_root).load_all()[0]

    assert fake_a.cancelled is True
    assert persisted.status == "PROCESSING"
    assert persisted.stage == "TRANSCRIBING"
    assert completion_a.calls == []

    fake_b = FakeManifestTranscriptionService(result=meeting_response("COMPLETED"))
    service_b = service(fake_b, store_root=store_root)
    await service_b.startup()
    final = await wait_for_terminal(service_b, started.processingId)

    assert final.status == "COMPLETED"
    assert fake_b.calls == 1
    await service_b.shutdown()


@pytest.mark.asyncio
async def test_progress_high_water_survives_restart(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    TranscriptionJobStore(store_root).save(
        PersistedTranscriptionJob(
            schema_version=SCHEMA_VERSION,
            request_id="minutes-job-15",
            processing_id="processing-15",
            meeting_id=12,
            manifest_paths=tuple(request().manifestPaths),
            status="PROCESSING",
            stage="TRANSCRIBING",
            processed_chunks=6,
            total_chunks=10,
            progress_percent=60,
            error=None,
            result=None,
        )
    )
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(
        progress=[(value, 10, float(value * 10)) for value in range(0, 11)]
    )
    job_service = service(fake, callback_client=callback_client, store_root=store_root)

    await job_service.startup()
    await wait_for_terminal(job_service, "processing-15")
    await wait_for_callback_count(callback_client, 2)

    progress_values = [payload.progressPercent for _, payload in callback_client.calls]
    processed_values = [payload.processedChunks for _, payload in callback_client.calls]
    assert min(progress_values) >= 60
    assert min(processed_values) >= 6
    assert 70.0 in progress_values
    assert 100.0 in progress_values
    await job_service.shutdown()


@pytest.mark.asyncio
async def test_startup_fails_on_conflicting_durable_request_id(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    store = TranscriptionJobStore(store_root)
    store.save(
        PersistedTranscriptionJob(
            schema_version=SCHEMA_VERSION,
            request_id="minutes-job-15",
            processing_id="processing-15",
            meeting_id=12,
            manifest_paths=("meeting-12/session-a/manifest.json",),
            status="QUEUED",
            stage="QUEUED",
            processed_chunks=0,
            total_chunks=0,
            progress_percent=0,
            error=None,
            result=None,
        )
    )
    store.save(
        PersistedTranscriptionJob(
            schema_version=SCHEMA_VERSION,
            request_id="minutes-job-15",
            processing_id="processing-16",
            meeting_id=13,
            manifest_paths=("meeting-13/session-a/manifest.json",),
            status="QUEUED",
            stage="QUEUED",
            processed_chunks=0,
            total_chunks=0,
            progress_percent=0,
            error=None,
            result=None,
        )
    )
    job_service = service(FakeManifestTranscriptionService(), store_root=store_root)

    with pytest.raises(Exception, match="Conflicting STT requestId records"):
        await job_service.startup()


@pytest.mark.asyncio
async def test_initial_progress_schedules_callback() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0)], block_after_progress=True)
    job_service = service(fake, callback_client)
    started = await job_service.start_job(request())

    await fake.progress_done.wait()
    await wait_for_callback_count(callback_client, 1)
    status = await job_service.get_status(started.processingId)
    fake.release.set()
    await wait_for_terminal(job_service, started.processingId)

    processing_id, payload = callback_client.calls[0]
    assert processing_id == started.processingId
    assert payload.meetingId == 12
    assert payload.processedChunks == 0
    assert payload.totalChunks == 4
    assert payload.progressPercent == 0.0
    assert status.processedChunks == 0
    assert status.totalChunks == 4
    assert status.progressPercent == 0.0


@pytest.mark.asyncio
async def test_mid_progress_schedules_callback() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (2, 4, 50.0)], block_after_progress=True)
    job_service = service(fake, callback_client)
    started = await job_service.start_job(request())

    await fake.progress_done.wait()
    await wait_for_callback_count(callback_client, 2)
    fake.release.set()
    await wait_for_terminal(job_service, started.processingId)

    assert [call[1].progressPercent for call in callback_client.calls[:2]] == [0.0, 50.0]


@pytest.mark.asyncio
async def test_terminal_progress_schedules_100_callback_only() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (4, 4, 100.0)])
    job_service = service(fake, callback_client)
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(callback_client, 2)

    assert [call[1].progressPercent for call in callback_client.calls] == [0.0, 100.0]


@pytest.mark.asyncio
async def test_progress_callbacks_are_throttled_by_min_percent_step() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(value, 100, float(value)) for value in range(0, 7)])
    job_service = service(fake, callback_client, min_percent_step=5.0)
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(callback_client, 2)

    assert [call[1].progressPercent for call in callback_client.calls] == [0.0, 5.0]


@pytest.mark.asyncio
async def test_small_meeting_sends_each_progress_step() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (1, 4, 33.33), (2, 4, 66.67), (4, 4, 100.0)])
    job_service = service(fake, callback_client, min_percent_step=5.0)
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(callback_client, 4)

    assert [call[1].progressPercent for call in callback_client.calls] == [0.0, 33.33, 66.67, 100.0]


@pytest.mark.asyncio
async def test_callback_disabled_keeps_local_progress_without_sending() -> None:
    callback_client = FakeProgressCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (2, 4, 50.0)], block_after_progress=True)
    job_service = service(fake, callback_client, callback_enabled=False)
    started = await job_service.start_job(request())

    await fake.progress_done.wait()
    status = await job_service.get_status(started.processingId)
    fake.release.set()
    await wait_for_terminal(job_service, started.processingId)

    assert status.processedChunks == 2
    assert status.totalChunks == 4
    assert status.progressPercent == 50.0
    assert callback_client.calls == []


@pytest.mark.asyncio
async def test_callback_failure_does_not_fail_python_job() -> None:
    callback_client = FakeProgressCallbackClient(error=RuntimeError("java down"))
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (4, 4, 100.0)])
    job_service = service(fake, callback_client)
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(callback_client, 2)

    assert final.status == "COMPLETED"
    assert final.stage == "COMPLETED"


@pytest.mark.asyncio
async def test_callback_task_is_non_blocking() -> None:
    callback_client = FakeProgressCallbackClient(block=True)
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (4, 4, 100.0)])
    job_service = service(fake, callback_client)
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)

    assert final.status == "COMPLETED"
    assert callback_client.started.is_set()
    callback_client.release.set()
    await wait_for_callback_count(callback_client, 2)


@pytest.mark.asyncio
async def test_shutdown_cancels_callback_tasks_and_closes_client() -> None:
    callback_client = FakeProgressCallbackClient(block=True)
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0)], block_after_progress=True)
    job_service = service(fake, callback_client)
    await job_service.start_job(request())
    await fake.progress_done.wait()
    await callback_client.started.wait()

    await job_service.shutdown()

    assert callback_client.cancelled is True
    assert callback_client.closed is True
    assert job_service._callback_tasks == set()


@pytest.mark.asyncio
async def test_completed_job_schedules_completion_callback() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(
        FakeManifestTranscriptionService(
            result=meeting_response(
                "COMPLETED",
                total_manifests=2,
                total_segments=3,
                total_chunks=3,
                processed_chunks=3,
                succeeded_chunks=3,
                failed_chunks=0,
            )
        ),
        completion_callback_client=completion_client,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)
    await wait_for_callback_count(completion_client, 1)

    payload = completion_client.calls[0][1]
    assert final.status == "COMPLETED"
    assert result.result.status == "COMPLETED"
    assert payload.status == SttTerminalStatus.COMPLETED
    assert payload.totalManifests == 2
    assert payload.totalSegments == 3
    assert payload.totalChunks == 3
    assert payload.processedChunks == 3
    assert payload.succeededChunks == 3
    assert payload.failedChunks == 0
    assert payload.errorMessage is None


@pytest.mark.asyncio
async def test_result_ready_before_completion_callback_starts(tmp_path: Path) -> None:
    store_root = tmp_path / "_stt_jobs"
    completion_client = FakeCompletionCallbackClient(store_root=store_root)
    job_service = service(
        FakeManifestTranscriptionService(),
        completion_callback_client=completion_client,
        store_root=store_root,
    )
    completion_client.job_service = job_service
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)

    assert completion_client.status_when_called == "COMPLETED"
    assert completion_client.result_ready_when_called is True
    assert completion_client.durable_status_when_called == "COMPLETED"
    assert completion_client.durable_result_ready_when_called is True

    await job_service.shutdown()
    restarted = service(FakeManifestTranscriptionService(), store_root=store_root)
    await restarted.startup()
    result = await restarted.get_result(started.processingId)
    assert result.result.status == "COMPLETED"
    await restarted.shutdown()


@pytest.mark.asyncio
async def test_partial_job_schedules_completion_callback_with_warning_summary() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(
        FakeManifestTranscriptionService(
            result=meeting_response(
                "PARTIAL",
                total_chunks=10,
                processed_chunks=10,
                succeeded_chunks=9,
                failed_chunks=1,
            )
        ),
        completion_callback_client=completion_client,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)

    payload = completion_client.calls[0][1]
    assert final.status == "PARTIAL"
    assert payload.status == SttTerminalStatus.PARTIAL
    assert payload.totalChunks == 10
    assert payload.processedChunks == 10
    assert payload.succeededChunks == 9
    assert payload.failedChunks == 1
    assert payload.errorMessage == "1 STT chunk failed"


@pytest.mark.asyncio
async def test_failed_response_schedules_completion_callback_with_response_counts() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(
        FakeManifestTranscriptionService(
            result=meeting_response(
                "FAILED",
                total_chunks=3,
                processed_chunks=3,
                succeeded_chunks=0,
                failed_chunks=3,
            )
        ),
        completion_callback_client=completion_client,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)

    payload = completion_client.calls[0][1]
    assert final.status == "FAILED"
    assert payload.status == SttTerminalStatus.FAILED
    assert payload.totalChunks == 3
    assert payload.processedChunks == 3
    assert payload.failedChunks == 3
    assert payload.errorMessage == "provider failed"


@pytest.mark.asyncio
async def test_exception_schedules_failed_completion_callback() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(
        FakeManifestTranscriptionService(error=FileNotFoundError("Manifest not found")),
        completion_callback_client=completion_client,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)

    payload = completion_client.calls[0][1]
    assert final.status == "FAILED"
    assert payload.status == SttTerminalStatus.FAILED
    assert payload.totalManifests == 2
    assert payload.totalSegments == 0
    assert payload.totalChunks == 0
    assert payload.processedChunks == 0
    assert payload.succeededChunks == 0
    assert payload.failedChunks == 0
    assert payload.errorMessage == "Transcription processing failed"


@pytest.mark.asyncio
async def test_completion_callback_disabled_keeps_local_completed_result() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(
        FakeManifestTranscriptionService(),
        completion_callback_client=completion_client,
        completion_callback_enabled=False,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)

    assert final.status == "COMPLETED"
    assert result.result.status == "COMPLETED"
    assert completion_client.calls == []


@pytest.mark.asyncio
async def test_completion_callback_failure_does_not_change_completed_job() -> None:
    completion_client = FakeCompletionCallbackClient(error=RuntimeError("java down"))
    job_service = service(FakeManifestTranscriptionService(), completion_callback_client=completion_client)
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)
    await wait_for_callback_count(completion_client, 1)

    assert final.status == "COMPLETED"
    assert result.result.status == "COMPLETED"


@pytest.mark.asyncio
async def test_partial_completion_callback_failure_does_not_change_partial_job() -> None:
    completion_client = FakeCompletionCallbackClient(error=RuntimeError("java down"))
    job_service = service(
        FakeManifestTranscriptionService(result=meeting_response("PARTIAL")),
        completion_callback_client=completion_client,
    )
    started = await job_service.start_job(request())

    final = await wait_for_terminal(job_service, started.processingId)
    result = await job_service.get_result(started.processingId)
    await wait_for_callback_count(completion_client, 1)

    assert final.status == "PARTIAL"
    assert result.result.status == "PARTIAL"


@pytest.mark.asyncio
async def test_completion_callback_scheduled_once_after_progress_callbacks() -> None:
    completion_client = FakeCompletionCallbackClient()
    fake = FakeManifestTranscriptionService(progress=[(0, 4, 0.0), (1, 4, 33.0), (2, 4, 66.0), (4, 4, 100.0)])
    job_service = service(fake, completion_callback_client=completion_client)
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)
    await asyncio.sleep(0)

    assert len(completion_client.calls) == 1


@pytest.mark.asyncio
async def test_completion_callback_uses_same_processing_id() -> None:
    completion_client = FakeCompletionCallbackClient()
    job_service = service(FakeManifestTranscriptionService(), completion_callback_client=completion_client)
    started = await job_service.start_job(request())

    await wait_for_terminal(job_service, started.processingId)
    await wait_for_callback_count(completion_client, 1)

    assert completion_client.calls[0][0] == started.processingId


@pytest.mark.asyncio
async def test_shutdown_cancels_completion_callback_tasks_and_closes_client() -> None:
    completion_client = FakeCompletionCallbackClient(block=True)
    job_service = service(FakeManifestTranscriptionService(), completion_callback_client=completion_client)
    started = await job_service.start_job(request())
    await wait_for_terminal(job_service, started.processingId)
    await completion_client.started.wait()

    await job_service.shutdown()

    assert completion_client.cancelled is True
    assert completion_client.closed is True
    assert job_service._callback_tasks == set()
