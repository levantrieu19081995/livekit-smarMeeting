import asyncio
import json
import wave
from datetime import datetime
from pathlib import Path

import pytest

from app.core.config import Settings
from app.models.transcription import TranscribeManifestRequest, TranscribeMeetingRequest
from app.services.audio_chunk_service import AudioChunkPlan
from app.services.manifest_transcription_service import ManifestTranscriptionService, _WorkItem


SAMPLE_RATE = 48000


def write_wav(path: Path, duration_seconds: int, framerate: int = SAMPLE_RATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_frames = framerate * duration_seconds
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(framerate)
        wav_file.writeframes(b"\x00\x00" * total_frames)


def make_settings(root: Path, *, chunk_seconds: int = 2, concurrency: int = 2) -> Settings:
    return Settings(
        capture_root_dir=root,
        OPENAI_API_KEY="test-key",
        STT_CHUNK_SECONDS=chunk_seconds,
        STT_CONCURRENCY=concurrency,
        STT_MAX_RETRIES=0,
        STT_RETRY_BASE_SECONDS=0.0,
        STT_SPEECH_REGION_ENABLED=False,
    )


def write_manifest(root: Path, participants: list[dict], session_id: str = "session-abc") -> Path:
    session_dir = root / "meeting-12" / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = session_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "meetingId": 12,
                "sessionId": session_id,
                "participants": participants,
            }
        ),
        encoding="utf-8",
    )
    return manifest_path


def segment(segment_id: str, index: int, file_path: str, started_at: str) -> dict:
    return {
        "segmentId": segment_id,
        "segmentIndex": index,
        "filePath": file_path,
        "startedAt": started_at,
        "durationMs": 5000,
        "trackSid": "track-1",
    }


class FakeProvider:
    def __init__(self, text: str = " baseline transcript ") -> None:
        self.text = text
        self.calls: list[Path] = []

    async def transcribe(self, wav_path: Path) -> str:
        self.calls.append(wav_path)
        await asyncio.sleep(0)
        return self.text


def make_service(
    root: Path,
    *,
    chunk_seconds: int = 2,
    provider: FakeProvider | None = None,
) -> ManifestTranscriptionService:
    return ManifestTranscriptionService(
        settings=make_settings(root, chunk_seconds=chunk_seconds),
        stt_provider=provider or FakeProvider(),
    )


def test_build_work_items_preserves_participant_segment_and_source_identity(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/segment-001.wav"
    manifest = {
        "meetingId": 12,
        "sessionId": "session-identity",
        "participants": [
            {
                "attendeeId": "attendee-1",
                "connectionId": "connection-1",
                "participantIdentity": "livekit-identity-1",
                "displayName": "Speaker One",
                "segments": [
                    segment("segment-001", 7, file_path, "2026-09-24T10:00:00"),
                ],
            }
        ],
    }
    manifest_path = write_manifest(root, manifest["participants"], session_id="session-identity")
    wav_path = manifest_path.parent / file_path
    write_wav(wav_path, duration_seconds=5)
    service = make_service(root)

    work_items, failures = service._build_work_items(
        root.resolve(),
        manifest_path.parent,
        manifest,
        session_id="session-identity",
        isolate_wav_errors=False,
    )

    assert failures == []
    assert len(work_items) == 3
    assert {item.session_id for item in work_items} == {"session-identity"}
    assert all(item.participant["attendeeId"] == "attendee-1" for item in work_items)
    assert all(item.participant["displayName"] == "Speaker One" for item in work_items)
    assert all(item.segment["segmentId"] == "segment-001" for item in work_items)
    assert all(item.segment["segmentIndex"] == 7 for item in work_items)
    assert all(item.wav_path == wav_path for item in work_items)
    assert all(item.plan.sourcePath == wav_path for item in work_items)
    assert [item.plan.startMs for item in work_items] == [0, 2000, 4000]


@pytest.mark.asyncio
async def test_process_item_reports_raw_relative_chunk_start_for_materialized_temp_wav(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/segment-001.wav"
    manifest_path = write_manifest(
        root,
        [
            {
                "attendeeId": "attendee-1",
                "displayName": "Speaker One",
                "segments": [segment("segment-001", 1, file_path, "2026-09-24T10:00:00")],
            }
        ],
    )
    wav_path = manifest_path.parent / file_path
    write_wav(wav_path, duration_seconds=5)
    provider = FakeProvider(" temp chunk text ")
    service = make_service(root, provider=provider)
    work_items, failures = service._build_work_items(
        root.resolve(),
        manifest_path.parent,
        json.loads(manifest_path.read_text(encoding="utf-8")),
        session_id="session-abc",
        isolate_wav_errors=False,
    )
    assert failures == []

    result, failure = await service._process_item(work_items[1])

    assert failure is None
    assert result is not None
    assert result.chunkIndex == 2
    assert result.chunkStartMs == 2000
    assert result.chunkDurationMs == 2000
    assert result.absoluteStartTime == datetime.fromisoformat("2026-09-24T10:00:02")
    assert provider.calls[0].name == "chunk-002.wav"
    assert not provider.calls[0].exists()
    assert not list(wav_path.parent.glob("meeting-stt-chunks-*"))


@pytest.mark.asyncio
async def test_absolute_start_time_uses_segment_started_at_plus_raw_chunk_start_ms(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    wav_path = tmp_path / "absolute-source.wav"
    write_wav(wav_path, duration_seconds=1)
    service = make_service(root, provider=FakeProvider(" absolute text "))
    item = _WorkItem(
        session_id="session-absolute",
        participant={"attendeeId": "attendee-absolute", "displayName": "Absolute Speaker"},
        segment={
            "segmentId": "segment-absolute",
            "segmentIndex": 3,
            "startedAt": "2026-09-24T10:00:00",
            "filePath": "participants/attendee/absolute-source.wav",
        },
        wav_path=wav_path,
        plan=AudioChunkPlan(
            sourcePath=wav_path,
            chunkIndex=99,
            startFrame=0,
            frameCount=SAMPLE_RATE,
            startMs=1815100,
            durationMs=1000,
            useOriginal=True,
        ),
    )

    result, failure = await service._process_item(item)

    assert failure is None
    assert result is not None
    assert result.attendeeId == "attendee-absolute"
    assert result.speakerName == "Absolute Speaker"
    assert result.segmentId == "segment-absolute"
    assert result.segmentIndex == 3
    assert result.chunkStartMs == 1815100
    assert result.absoluteStartTime == datetime.fromisoformat("2026-09-24T10:30:15.100")


@pytest.mark.asyncio
async def test_manifest_transcription_carries_raw_offsets_and_identity_to_results(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/segment-001.wav"
    manifest_path = write_manifest(
        root,
        [
            {
                "attendeeId": "attendee-1",
                "displayName": "Speaker One",
                "segments": [segment("segment-001", 4, file_path, "2026-09-24T10:00:00")],
            }
        ],
    )
    write_wav(manifest_path.parent / file_path, duration_seconds=5)
    provider = FakeProvider(" transcript ")

    response = await make_service(root, provider=provider).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.status == "COMPLETED"
    assert response.totalSegments == 1
    assert response.totalChunks == 3
    assert response.processedChunks == 3
    assert response.succeededChunks == 3
    assert response.failedChunks == 0
    assert [item.attendeeId for item in response.transcripts] == ["attendee-1"] * 3
    assert [item.speakerName for item in response.transcripts] == ["Speaker One"] * 3
    assert [item.segmentId for item in response.transcripts] == ["segment-001"] * 3
    assert [item.segmentIndex for item in response.transcripts] == [4, 4, 4]
    assert [item.chunkStartMs for item in response.transcripts] == [0, 2000, 4000]
    assert [item.chunkDurationMs for item in response.transcripts] == [2000, 2000, 1000]
    assert [item.absoluteStartTime for item in response.transcripts] == [
        datetime.fromisoformat("2026-09-24T10:00:00"),
        datetime.fromisoformat("2026-09-24T10:00:02"),
        datetime.fromisoformat("2026-09-24T10:00:04"),
    ]
    assert len(provider.calls) == 3


@pytest.mark.asyncio
async def test_zero_work_item_manifest_is_currently_supported(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    manifest_path = write_manifest(root, [])
    provider = FakeProvider()
    progress: list[tuple[int, int, float]] = []

    response = await make_service(root, provider=provider).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"),
        progress_callback=lambda processed, total, percent: progress.append((processed, total, percent)) or asyncio.sleep(0),
    )

    assert manifest_path.exists()
    assert response.status == "COMPLETED"
    assert response.totalSegments == 0
    assert response.totalChunks == 0
    assert response.processedChunks == 0
    assert response.succeededChunks == 0
    assert response.failedChunks == 0
    assert response.transcripts == []
    assert response.failures == []
    assert provider.calls == []
    assert progress == [(0, 0, 100)]


@pytest.mark.asyncio
async def test_meeting_transcription_continues_to_keep_session_identity_per_manifest(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    manifest_a = write_manifest(
        root,
        [
            {
                "attendeeId": "attendee-a",
                "displayName": "Speaker A",
                "segments": [segment("same-segment", 1, "participants/a/seg.wav", "2026-09-24T10:00:01")],
            }
        ],
        session_id="session-a",
    )
    manifest_b = write_manifest(
        root,
        [
            {
                "attendeeId": "attendee-b",
                "displayName": "Speaker B",
                "segments": [segment("same-segment", 1, "participants/b/seg.wav", "2026-09-24T10:00:02")],
            }
        ],
        session_id="session-b",
    )
    write_wav(manifest_a.parent / "participants/a/seg.wav", duration_seconds=1)
    write_wav(manifest_b.parent / "participants/b/seg.wav", duration_seconds=1)

    response = await make_service(root, chunk_seconds=180).transcribe_meeting(
        TranscribeMeetingRequest(
            requestId="baseline-job",
            meetingId=12,
            manifestPaths=[
                "meeting-12/session-a/manifest.json",
                "meeting-12/session-b/manifest.json",
            ],
        )
    )

    assert response.status == "COMPLETED"
    assert response.sessionIds == ["session-a", "session-b"]
    assert response.totalManifests == 2
    assert response.totalSegments == 2
    assert response.totalChunks == 2
    assert [(item.sessionId, item.attendeeId, item.speakerName, item.segmentId) for item in response.transcripts] == [
        ("session-a", "attendee-a", "Speaker A", "same-segment"),
        ("session-b", "attendee-b", "Speaker B", "same-segment"),
    ]
