import json
import shutil
import uuid
from pathlib import Path

import pytest

from app.models.transcription import (
    FailedTranscriptChunk,
    TranscribeMeetingResponse,
    TranscriptChunkResult,
)
from app.services.transcription_job_store import (
    SCHEMA_VERSION,
    PersistedTranscriptionJob,
    TranscriptionJobStore,
    TranscriptionJobStoreError,
)


@pytest.fixture
def tmp_path() -> Path:
    path = Path.cwd() / "test_workspace_tmp" / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def result(status: str = "COMPLETED") -> TranscribeMeetingResponse:
    succeeded = 0 if status == "FAILED" else 1
    failed = 1 if status == "FAILED" else 0
    return TranscribeMeetingResponse(
        meetingId=12,
        sessionIds=["session-a"],
        status=status,
        totalManifests=1,
        totalSegments=1,
        totalChunks=1,
        processedChunks=1,
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
                segmentId="segment-1",
                chunkIndex=1,
                filePath="participants/a1/segment-1.wav",
                attempts=1,
                error="provider failed",
            )
        ]
        if failed
        else [],
    )


def queued_record(processing_id: str = "processing-1") -> PersistedTranscriptionJob:
    return PersistedTranscriptionJob(
        schema_version=SCHEMA_VERSION,
        request_id="minutes-job-15",
        processing_id=processing_id,
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


def record_with(**overrides) -> PersistedTranscriptionJob:
    return PersistedTranscriptionJob(
        **{
            **queued_record().__dict__,
            **overrides,
        }
    )


def assert_rejected(job: PersistedTranscriptionJob, tmp_path: Path) -> None:
    with pytest.raises(TranscriptionJobStoreError):
        TranscriptionJobStore(tmp_path).save(job)


def test_save_and_load_valid_queued_record(tmp_path: Path) -> None:
    store = TranscriptionJobStore(tmp_path)

    store.save(queued_record())

    loaded = store.load_all()
    assert loaded == [queued_record()]


def test_save_replaces_updated_state(tmp_path: Path) -> None:
    store = TranscriptionJobStore(tmp_path)
    store.save(queued_record())
    updated = PersistedTranscriptionJob(
        **{
            **queued_record().__dict__,
            "status": "PROCESSING",
            "stage": "TRANSCRIBING",
            "processed_chunks": 1,
            "total_chunks": 4,
            "progress_percent": 25.0,
        }
    )

    store.save(updated)

    assert store.load_all() == [updated]
    assert not list(tmp_path.glob("*.tmp"))


def test_terminal_result_round_trip(tmp_path: Path) -> None:
    store = TranscriptionJobStore(tmp_path)
    terminal = PersistedTranscriptionJob(
        **{
            **queued_record().__dict__,
            "status": "COMPLETED",
            "stage": "COMPLETED",
            "processed_chunks": 1,
            "total_chunks": 1,
            "progress_percent": 100.0,
            "result": result(),
        }
    )

    store.save(terminal)

    loaded = store.load_all()[0]
    assert loaded.result is not None
    assert loaded.result.status == "COMPLETED"
    assert loaded.result.transcripts[0].text == "hello"


def test_malformed_json_causes_load_failure(tmp_path: Path) -> None:
    (tmp_path / "processing-1.json").write_text("{not-json", encoding="utf-8")

    with pytest.raises(TranscriptionJobStoreError):
        TranscriptionJobStore(tmp_path).load_all()


def test_unsupported_schema_causes_load_failure(tmp_path: Path) -> None:
    payload = {
        "schemaVersion": "999",
        "requestId": "minutes-job-15",
        "processingId": "processing-1",
        "meetingId": 12,
        "manifestPaths": ["meeting-12/session-a/manifest.json"],
        "status": "QUEUED",
        "stage": "QUEUED",
        "processedChunks": 0,
        "totalChunks": 0,
        "progressPercent": 0,
        "error": None,
        "result": None,
    }
    (tmp_path / "processing-1.json").write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(TranscriptionJobStoreError):
        TranscriptionJobStore(tmp_path).load_all()


@pytest.mark.parametrize(
    ("status", "stage", "job_result"),
    [
        ("PROCESSING", "COMPLETED", None),
        ("QUEUED", "FAILED", None),
        ("COMPLETED", "TRANSCRIBING", result("COMPLETED")),
    ],
)
def test_invalid_status_stage_pairs_are_rejected(
    tmp_path: Path,
    status: str,
    stage: str,
    job_result: TranscribeMeetingResponse | None,
) -> None:
    assert_rejected(
        record_with(
            status=status,
            stage=stage,
            processed_chunks=1 if job_result is not None else 0,
            total_chunks=1 if job_result is not None else 0,
            progress_percent=100 if job_result is not None else 0,
            result=job_result,
        ),
        tmp_path,
    )


def test_failed_record_rejects_result_status_mismatch(tmp_path: Path) -> None:
    assert_rejected(
        record_with(
            status="FAILED",
            stage="FAILED",
            processed_chunks=1,
            total_chunks=1,
            progress_percent=100,
            result=result("COMPLETED"),
        ),
        tmp_path,
    )


def test_failed_record_rejects_result_meeting_id_mismatch(tmp_path: Path) -> None:
    mismatched_result = result("FAILED").model_copy(update={"meetingId": 99})

    assert_rejected(
        record_with(
            status="FAILED",
            stage="FAILED",
            processed_chunks=1,
            total_chunks=1,
            progress_percent=100,
            result=mismatched_result,
        ),
        tmp_path,
    )


@pytest.mark.parametrize(
    "job",
    [
        queued_record(),
        record_with(
            status="PROCESSING",
            stage="TRANSCRIBING",
            processed_chunks=1,
            total_chunks=4,
            progress_percent=25,
        ),
        record_with(
            status="COMPLETED",
            stage="COMPLETED",
            processed_chunks=1,
            total_chunks=1,
            progress_percent=100,
            result=result("COMPLETED"),
        ),
        record_with(
            status="PARTIAL",
            stage="COMPLETED",
            processed_chunks=1,
            total_chunks=1,
            progress_percent=100,
            result=result("PARTIAL"),
        ),
        record_with(
            status="FAILED",
            stage="FAILED",
            processed_chunks=0,
            total_chunks=0,
            progress_percent=0,
            error="provider failed",
            result=None,
        ),
        record_with(
            status="FAILED",
            stage="FAILED",
            processed_chunks=1,
            total_chunks=1,
            progress_percent=100,
            error="provider failed",
            result=result("FAILED"),
        ),
    ],
)
def test_valid_status_stage_and_result_combinations_are_preserved(
    tmp_path: Path,
    job: PersistedTranscriptionJob,
) -> None:
    store = TranscriptionJobStore(tmp_path)

    store.save(job)

    assert store.load_all() == [job]
