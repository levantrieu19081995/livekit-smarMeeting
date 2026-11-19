import json
import uuid
import wave
from pathlib import Path

import pytest
from fastapi import HTTPException, status

from app.core.config import Settings
from app.models.transcription import TranscribeSegmentRequest
from app.services.audio_chunk_service import AudioChunkService
from app.services.transcription_service import TranscriptionService


class FakeSttProvider:
    def __init__(self, texts: list[str] | None = None, fail_on_call: int | None = None) -> None:
        self.texts = texts or ["noi dung tieng viet mock"]
        self.fail_on_call = fail_on_call
        self.calls: list[Path] = []

    async def transcribe(self, wav_path: Path) -> str:
        self.calls.append(wav_path)
        if self.fail_on_call == len(self.calls):
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="STT provider error: failed chunk")
        return self.texts[len(self.calls) - 1]


class TrackingAudioChunkService(AudioChunkService):
    def __init__(self, chunk_seconds: int) -> None:
        super().__init__(chunk_seconds)
        self.created_chunks = []

    def materialize_chunk(self, plan):
        chunk = super().materialize_chunk(plan)
        self.created_chunks.append(chunk)
        return chunk


def make_settings(root: Path, chunk_seconds: int = 180) -> Settings:
    return Settings(
        capture_root_dir=root,
        OPENAI_API_KEY="test-key",
        STT_MODEL="mock-stt-model",
        STT_CHUNK_SECONDS=chunk_seconds,
    )


def work_dir() -> Path:
    path = Path("test_output") / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_manifest(root: Path, *, file_path: str = "participants/attendee-1/segment-001_TR_1.wav") -> Path:
    session_dir = root / "meeting-12" / "session-abc"
    session_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "meetingId": 12,
        "sessionId": "session-abc",
        "participants": [
            {
                "attendeeId": "attendee-1",
                "displayName": "Nguyen Van A",
                "segments": [
                    {
                        "segmentId": "segment-id",
                        "segmentIndex": 1,
                        "filePath": file_path,
                        "startedAt": "2026-08-26T00:27:57.924976Z",
                        "durationMs": 120000,
                    }
                ],
            }
        ],
    }
    manifest_path = session_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path


def write_wav(path: Path, duration_seconds: int = 1, framerate: int = 8000) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(framerate)
        wav_file.writeframes(b"\x00\x00" * framerate * duration_seconds)


def request(manifest_path: str = "meeting-12/session-abc/manifest.json", segment_id: str = "segment-id") -> TranscribeSegmentRequest:
    return TranscribeSegmentRequest(meetingId=12, manifestPath=manifest_path, segmentId=segment_id)


@pytest.mark.asyncio
async def test_transcribes_matching_segment_and_returns_metadata() -> None:
    root = work_dir() / "meetings"
    manifest_path = write_manifest(root)
    wav_path = manifest_path.parent / "participants" / "attendee-1" / "segment-001_TR_1.wav"
    write_wav(wav_path)
    provider = FakeSttProvider()

    service = TranscriptionService(settings=make_settings(root), stt_provider=provider)
    response = await service.transcribe_segment(request())

    assert response.meetingId == 12
    assert response.sessionId == "session-abc"
    assert response.attendeeId == "attendee-1"
    assert response.speakerName == "Nguyen Van A"
    assert response.segmentId == "segment-id"
    assert response.segmentIndex == 1
    assert response.durationMs == 120000
    assert response.filePath == "participants/attendee-1/segment-001_TR_1.wav"
    assert response.text == "noi dung tieng viet mock"
    assert provider.calls == [wav_path.resolve()]


@pytest.mark.asyncio
async def test_segment_not_found_returns_404() -> None:
    root = work_dir() / "meetings"
    write_manifest(root)
    service = TranscriptionService(settings=make_settings(root), stt_provider=FakeSttProvider())

    with pytest.raises(HTTPException) as exc_info:
        await service.transcribe_segment(request(segment_id="missing-segment"))

    assert exc_info.value.status_code == 404
    assert exc_info.value.detail == "Segment not found"


@pytest.mark.asyncio
async def test_wav_not_found_returns_404() -> None:
    root = work_dir() / "meetings"
    write_manifest(root)
    service = TranscriptionService(settings=make_settings(root), stt_provider=FakeSttProvider())

    with pytest.raises(HTTPException) as exc_info:
        await service.transcribe_segment(request())

    assert exc_info.value.status_code == 404
    assert exc_info.value.detail == "WAV file not found"


@pytest.mark.asyncio
async def test_segment_file_path_outside_capture_root_returns_400() -> None:
    root = work_dir() / "meetings"
    write_manifest(root, file_path="../../../../outside.wav")
    service = TranscriptionService(settings=make_settings(root), stt_provider=FakeSttProvider())

    with pytest.raises(HTTPException) as exc_info:
        await service.transcribe_segment(request())

    assert exc_info.value.status_code == 400
    assert exc_info.value.detail == "Path is outside CAPTURE_ROOT_DIR"


@pytest.mark.asyncio
async def test_short_wav_calls_provider_once() -> None:
    root = work_dir() / "meetings"
    manifest_path = write_manifest(root)
    wav_path = manifest_path.parent / "participants" / "attendee-1" / "segment-001_TR_1.wav"
    write_wav(wav_path, duration_seconds=1)
    provider = FakeSttProvider(["  mot doan ngan  "])

    service = TranscriptionService(settings=make_settings(root, chunk_seconds=180), stt_provider=provider)
    response = await service.transcribe_segment(request())

    assert provider.calls == [wav_path.resolve()]
    assert response.text == "mot doan ngan"


@pytest.mark.asyncio
async def test_long_wav_transcribes_chunks_sequentially_and_joins_text() -> None:
    root = work_dir() / "meetings"
    manifest_path = write_manifest(root)
    wav_path = manifest_path.parent / "participants" / "attendee-1" / "segment-001_TR_1.wav"
    write_wav(wav_path, duration_seconds=5)
    provider = FakeSttProvider(["Đoạn một", "Đoạn hai", "Đoạn ba"])

    service = TranscriptionService(settings=make_settings(root, chunk_seconds=2), stt_provider=provider)
    response = await service.transcribe_segment(request())

    assert len(provider.calls) == 3
    assert [path.name for path in provider.calls] == ["chunk-001.wav", "chunk-002.wav", "chunk-003.wav"]
    assert response.text == "Đoạn một\nĐoạn hai\nĐoạn ba"
    assert all(not path.exists() for path in provider.calls)


@pytest.mark.asyncio
async def test_provider_error_cleans_up_temporary_chunks() -> None:
    root = work_dir() / "meetings"
    manifest_path = write_manifest(root)
    wav_path = manifest_path.parent / "participants" / "attendee-1" / "segment-001_TR_1.wav"
    write_wav(wav_path, duration_seconds=5)
    provider = FakeSttProvider(["chunk one", "chunk two", "chunk three"], fail_on_call=2)
    chunk_service = TrackingAudioChunkService(chunk_seconds=2)

    service = TranscriptionService(
        settings=make_settings(root, chunk_seconds=2),
        stt_provider=provider,
        audio_chunk_service=chunk_service,
    )

    with pytest.raises(HTTPException) as exc_info:
        await service.transcribe_segment(request())

    assert exc_info.value.status_code == 502
    assert all(not chunk.path.exists() for chunk in chunk_service.created_chunks)
    assert wav_path.exists()
