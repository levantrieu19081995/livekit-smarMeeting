import shutil
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.transcription import manifest_transcription_service, transcription_job_service, transcription_service
from app.core.config import Settings
from app.main import app
from app.models.transcription import (
    StartTranscriptionJobResponse,
    TranscribeManifestResponse,
    TranscribeMeetingResponse,
    TranscribeSegmentResponse,
    TranscriptionJobResultResponse,
    TranscriptionJobStatusResponse,
    TranscriptChunkResult,
)


@pytest.fixture
def tmp_path() -> Path:
    path = Path.cwd() / "test_workspace_tmp" / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


class FakeTranscriptionService:
    async def transcribe_segment(self, request):
        return TranscribeSegmentResponse(
            meetingId=request.meetingId,
            sessionId="session-xxx",
            attendeeId="attendee-1",
            speakerName="Nguyen Van A",
            segmentId=request.segmentId,
            segmentIndex=1,
            segmentStartedAt=None,
            durationMs=120000,
            filePath="participants/attendee-1/segment-001_TR_1.wav",
            text="noi dung tieng viet mock",
        )


def test_transcribe_segment_endpoint_returns_response() -> None:
    app.dependency_overrides[transcription_service] = lambda: FakeTranscriptionService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).post(
            "/api/postprocessing/stt/segments/transcribe",
            json={
                "meetingId": 12,
                "manifestPath": "meeting-12/session-xxx/manifest.json",
                "segmentId": "segment-id",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["meetingId"] == 12
    assert body["segmentId"] == "segment-id"
    assert body["text"] == "noi dung tieng viet mock"


class FakeManifestTranscriptionService:
    async def transcribe_manifest(self, request):
        return TranscribeManifestResponse(
            meetingId=request.meetingId,
            sessionId="session-xxx",
            status="COMPLETED",
            totalSegments=1,
            totalChunks=1,
            processedChunks=1,
            succeededChunks=1,
            failedChunks=0,
            transcripts=[
                TranscriptChunkResult(
                    sessionId="session-xxx",
                    attendeeId="attendee-1",
                    speakerName="Nguyen Van A",
                    segmentId="segment-id",
                    segmentIndex=1,
                    chunkIndex=1,
                    chunkStartMs=0,
                    chunkDurationMs=120000,
                    segmentStartedAt=None,
                    absoluteStartTime=None,
                    filePath="participants/attendee-1/segment-001_TR_1.wav",
                    text="noi dung manifest mock",
                )
            ],
            failures=[],
        )


def test_transcribe_manifest_endpoint_returns_response() -> None:
    app.dependency_overrides[manifest_transcription_service] = lambda: FakeManifestTranscriptionService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).post(
            "/api/postprocessing/stt/manifests/transcribe",
            json={
                "meetingId": 12,
                "manifestPath": "meeting-12/session-xxx/manifest.json",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["meetingId"] == 12
    assert body["totalChunks"] == 1
    assert body["status"] == "COMPLETED"
    assert body["transcripts"][0]["text"] == "noi dung manifest mock"


class FakeJobService:
    async def start_job(self, request):
        return StartTranscriptionJobResponse(
            processingId="processing-1",
            meetingId=request.meetingId,
            status="QUEUED",
        )

    async def get_status(self, processing_id):
        return TranscriptionJobStatusResponse(
            processingId=processing_id,
            meetingId=12,
            status="PROCESSING",
            stage="TRANSCRIBING",
            processedChunks=2,
            totalChunks=4,
            progressPercent=50,
        )

    async def get_result(self, processing_id):
        return TranscriptionJobResultResponse(
            processingId=processing_id,
            result=TranscribeMeetingResponse(
                meetingId=12,
                sessionIds=["session-a", "session-b"],
                status="COMPLETED",
                totalManifests=2,
                totalSegments=1,
                totalChunks=1,
                processedChunks=1,
                succeededChunks=1,
                failedChunks=0,
                transcripts=[
                    TranscriptChunkResult(
                        sessionId="session-xxx",
                        attendeeId="attendee-1",
                        speakerName="Nguyen Van A",
                        segmentId="segment-id",
                        segmentIndex=1,
                        chunkIndex=1,
                        chunkStartMs=0,
                        chunkDurationMs=120000,
                        segmentStartedAt=None,
                        absoluteStartTime=None,
                        filePath="participants/attendee-1/segment-001_TR_1.wav",
                        text="job result mock",
                    )
                ],
                failures=[],
            ),
        )


def test_start_transcription_job_endpoint_returns_202() -> None:
    app.dependency_overrides[transcription_job_service] = lambda: FakeJobService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).post(
            "/api/postprocessing/stt/jobs",
            json={
                "meetingId": 12,
                "requestId": "minutes-job-15",
                "manifestPaths": [
                    "meeting-12/session-a/manifest.json",
                    "meeting-12/session-b/manifest.json",
                ],
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 202
    assert response.json() == {
        "processingId": "processing-1",
        "meetingId": 12,
        "status": "QUEUED",
    }


def test_start_transcription_job_rejects_blank_request_id() -> None:
    app.dependency_overrides[transcription_job_service] = lambda: FakeJobService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).post(
            "/api/postprocessing/stt/jobs",
            json={
                "meetingId": 12,
                "requestId": " ",
                "manifestPaths": [
                    "meeting-12/session-a/manifest.json",
                ],
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422


def test_duplicate_transcription_job_post_returns_same_processing_id(tmp_path) -> None:
    from app.services.transcription_job_service import TranscriptionJobService

    class ImmediateManifestService:
        async def transcribe_meeting(self, request, progress_callback=None):
            return TranscribeMeetingResponse(
                meetingId=request.meetingId,
                sessionIds=["session-a"],
                status="COMPLETED",
                totalManifests=1,
                totalSegments=1,
                totalChunks=1,
                processedChunks=1,
                succeededChunks=1,
                failedChunks=0,
                transcripts=[],
                failures=[],
            )

    job_service = TranscriptionJobService(
        ImmediateManifestService(),
        settings=Settings(capture_root_dir=tmp_path),
    )
    app.dependency_overrides[transcription_job_service] = lambda: job_service
    try:
        client = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"})
        payload = {
            "meetingId": 12,
            "requestId": "minutes-job-15",
            "manifestPaths": [
                "meeting-12/session-a/manifest.json",
            ],
        }
        first = client.post("/api/postprocessing/stt/jobs", json=payload)
        second = client.post("/api/postprocessing/stt/jobs", json=payload)
    finally:
        app.dependency_overrides.clear()

    assert first.status_code == 202
    assert second.status_code == 202
    assert second.json()["processingId"] == first.json()["processingId"]


def test_duplicate_transcription_job_post_with_conflict_returns_409(tmp_path) -> None:
    from app.services.transcription_job_service import TranscriptionJobService

    class ImmediateManifestService:
        async def transcribe_meeting(self, request, progress_callback=None):
            return TranscribeMeetingResponse(
                meetingId=request.meetingId,
                sessionIds=["session-a"],
                status="COMPLETED",
                totalManifests=1,
                totalSegments=1,
                totalChunks=1,
                processedChunks=1,
                succeededChunks=1,
                failedChunks=0,
                transcripts=[],
                failures=[],
            )

    job_service = TranscriptionJobService(
        ImmediateManifestService(),
        settings=Settings(capture_root_dir=tmp_path),
    )
    app.dependency_overrides[transcription_job_service] = lambda: job_service
    try:
        client = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"})
        first = client.post(
            "/api/postprocessing/stt/jobs",
            json={
                "meetingId": 12,
                "requestId": "minutes-job-15",
                "manifestPaths": [
                    "meeting-12/session-a/manifest.json",
                ],
            },
        )
        conflict = client.post(
            "/api/postprocessing/stt/jobs",
            json={
                "meetingId": 13,
                "requestId": "minutes-job-15",
                "manifestPaths": [
                    "meeting-12/session-a/manifest.json",
                ],
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert first.status_code == 202
    assert conflict.status_code == 409


def test_get_transcription_job_status_endpoint_returns_progress() -> None:
    app.dependency_overrides[transcription_job_service] = lambda: FakeJobService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).get("/api/postprocessing/stt/jobs/processing-1")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["processingId"] == "processing-1"
    assert body["status"] == "PROCESSING"
    assert body["progressPercent"] == 50


def test_get_transcription_job_result_endpoint_returns_result() -> None:
    app.dependency_overrides[transcription_job_service] = lambda: FakeJobService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).get("/api/postprocessing/stt/jobs/processing-1/result")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["processingId"] == "processing-1"
    assert body["result"]["status"] == "COMPLETED"
    assert body["result"]["transcripts"][0]["text"] == "job result mock"


def test_start_transcription_job_rejects_empty_manifest_paths() -> None:
    app.dependency_overrides[transcription_job_service] = lambda: FakeJobService()
    try:
        response = TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}).post(
            "/api/postprocessing/stt/jobs",
            json={
                "meetingId": 12,
                "requestId": "minutes-job-15",
                "manifestPaths": [],
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422
