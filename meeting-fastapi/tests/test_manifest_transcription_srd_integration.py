import asyncio
import hashlib
import json
import logging
import wave
from datetime import datetime
from pathlib import Path

import pytest

from app.core.config import Settings
from app.models.transcription import TranscribeManifestRequest, TranscribeMeetingRequest
from app.services.audio_chunk_service import AudioChunkService
from app.services.manifest_transcription_service import ManifestTranscriptionService
from app.services.speech_region_service import SpeechRegion, SpeechRegionDetectionError


SAMPLE_RATE = 48000


def write_wav(path: Path, duration_seconds: int, framerate: int = SAMPLE_RATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_frames = framerate * duration_seconds
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(framerate)
        wav_file.writeframes(b"\x00\x00" * total_frames)


def hash_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def region(start_seconds: int, duration_seconds: int, framerate: int = SAMPLE_RATE) -> SpeechRegion:
    start_frame = start_seconds * framerate
    frame_count = duration_seconds * framerate
    return SpeechRegion(
        startFrame=start_frame,
        frameCount=frame_count,
        startMs=start_seconds * 1000,
        durationMs=duration_seconds * 1000,
    )


def make_settings(
    root: Path,
    *,
    chunk_seconds: int = 180,
    concurrency: int = 2,
    speech_region_enabled: bool = True,
) -> Settings:
    return Settings(
        capture_root_dir=root,
        OPENAI_API_KEY="test-key",
        STT_CHUNK_SECONDS=chunk_seconds,
        STT_CONCURRENCY=concurrency,
        STT_MAX_RETRIES=0,
        STT_RETRY_BASE_SECONDS=0.0,
        STT_SPEECH_REGION_ENABLED=speech_region_enabled,
    )


def segment(segment_id: str, index: int, file_path: str, started_at: str = "2026-09-24T10:00:00") -> dict:
    return {
        "segmentId": segment_id,
        "segmentIndex": index,
        "filePath": file_path,
        "startedAt": started_at,
        "durationMs": 5000,
        "trackSid": f"track-{segment_id}",
    }


def write_manifest(
    root: Path,
    participants: list[dict],
    *,
    meeting_id: int = 12,
    session_id: str = "session-abc",
) -> Path:
    session_dir = root / f"meeting-{meeting_id}" / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = session_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "meetingId": meeting_id,
                "sessionId": session_id,
                "participants": participants,
            }
        ),
        encoding="utf-8",
    )
    return manifest_path


class FakeProvider:
    def __init__(self, text: str = " fake transcript ", error: Exception | None = None) -> None:
        self.text = text
        self.error = error
        self.calls: list[Path] = []
        self.exists_during_call: list[bool] = []

    async def transcribe(self, wav_path: Path) -> str:
        self.calls.append(wav_path)
        self.exists_during_call.append(wav_path.exists())
        await asyncio.sleep(0)
        if self.error is not None:
            raise self.error
        return self.text


class FakeSpeechRegionService:
    def __init__(self, results_by_name: dict[str, list[SpeechRegion] | Exception]) -> None:
        self.results_by_name = results_by_name
        self.calls: list[Path] = []

    def detect(self, wav_path: Path) -> list[SpeechRegion]:
        self.calls.append(wav_path)
        result = self.results_by_name[wav_path.name]
        if isinstance(result, Exception):
            raise result
        return result


class FailsIfCalledSpeechRegionService:
    def __init__(self) -> None:
        self.calls: list[Path] = []

    def detect(self, wav_path: Path) -> list[SpeechRegion]:
        self.calls.append(wav_path)
        raise AssertionError("SRD should not be called")


class TrackingAudioChunkService(AudioChunkService):
    def __init__(
        self,
        chunk_seconds: int,
        *,
        plan_speech_error: Exception | None = None,
        legacy_error: Exception | None = None,
    ) -> None:
        super().__init__(chunk_seconds=chunk_seconds)
        self.plan_speech_error = plan_speech_error
        self.legacy_error = legacy_error
        self.plan_chunks_calls: list[Path] = []
        self.plan_speech_regions_calls: list[tuple[Path, list[SpeechRegion]]] = []

    def plan_chunks(self, wav_path: Path):
        self.plan_chunks_calls.append(wav_path)
        if self.legacy_error is not None:
            raise self.legacy_error
        return super().plan_chunks(wav_path)

    def plan_speech_regions(self, wav_path: Path, regions: list[SpeechRegion]):
        self.plan_speech_regions_calls.append((wav_path, regions))
        if self.plan_speech_error is not None:
            raise self.plan_speech_error
        return super().plan_speech_regions(wav_path, regions)


def make_service(
    root: Path,
    *,
    provider: FakeProvider | None = None,
    speech_region_service: object | None = None,
    audio_chunk_service: AudioChunkService | None = None,
    chunk_seconds: int = 180,
    concurrency: int = 2,
    speech_region_enabled: bool = True,
) -> ManifestTranscriptionService:
    return ManifestTranscriptionService(
        settings=make_settings(
            root,
            chunk_seconds=chunk_seconds,
            concurrency=concurrency,
            speech_region_enabled=speech_region_enabled,
        ),
        stt_provider=provider or FakeProvider(),
        audio_chunk_service=audio_chunk_service,
        speech_region_service=speech_region_service,
    )


def single_segment_participants(file_path: str, segment_id: str = "segment-001") -> list[dict]:
    return [
        {
            "attendeeId": "attendee-1",
            "displayName": "Speaker One",
            "segments": [segment(segment_id, 4, file_path)],
        }
    ]


@pytest.mark.asyncio
async def test_srd_success_uses_speech_region_planning_and_preserves_raw_timing(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=5)
    provider = FakeProvider(" speech text ")
    srd = FakeSpeechRegionService({"speech.wav": [region(1, 2)]})
    chunks = TrackingAudioChunkService(chunk_seconds=180)

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
    ).transcribe_manifest(TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"))

    assert response.status == "COMPLETED"
    assert response.totalSegments == 1
    assert response.totalChunks == 1
    assert response.processedChunks == 1
    assert len(provider.calls) == 1
    assert len(srd.calls) == 1
    assert len(chunks.plan_speech_regions_calls) == 1
    assert chunks.plan_chunks_calls == []
    transcript = response.transcripts[0]
    assert transcript.sessionId == "session-abc"
    assert transcript.attendeeId == "attendee-1"
    assert transcript.speakerName == "Speaker One"
    assert transcript.segmentId == "segment-001"
    assert transcript.segmentIndex == 4
    assert transcript.chunkIndex == 1
    assert transcript.chunkStartMs == 1000
    assert transcript.chunkDurationMs == 2000
    assert transcript.absoluteStartTime == datetime.fromisoformat("2026-09-24T10:00:01")
    assert transcript.filePath == file_path
    assert transcript.text == "speech text"


@pytest.mark.asyncio
async def test_valid_silence_has_no_fallback_no_stt_and_zero_chunk_progress(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/silent.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=3)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"silent.wav": []})
    chunks = TrackingAudioChunkService(chunk_seconds=180)
    progress: list[tuple[int, int, float]] = []

    async def record_progress(processed: int, total: int, percent: float) -> None:
        progress.append((processed, total, percent))

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
    ).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"),
        progress_callback=record_progress,
    )

    assert response.status == "COMPLETED"
    assert response.totalSegments == 1
    assert response.totalChunks == 0
    assert response.processedChunks == 0
    assert response.succeededChunks == 0
    assert response.failedChunks == 0
    assert response.transcripts == []
    assert response.failures == []
    assert provider.calls == []
    assert len(chunks.plan_speech_regions_calls) == 1
    assert chunks.plan_chunks_calls == []
    assert progress == [(0, 0, 100)]


@pytest.mark.asyncio
async def test_mixed_silent_and_speaking_segments_count_all_segments_but_only_speech_chunks(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    participants = [
        {
            "attendeeId": "attendee-1",
            "displayName": "Speaker One",
            "segments": [
                segment("silent-segment", 1, "participants/attendee-1/silent.wav"),
                segment("speech-segment", 2, "participants/attendee-1/speech.wav"),
            ],
        }
    ]
    manifest_path = write_manifest(root, participants)
    write_wav(manifest_path.parent / "participants/attendee-1/silent.wav", duration_seconds=3)
    write_wav(manifest_path.parent / "participants/attendee-1/speech.wav", duration_seconds=3)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"silent.wav": [], "speech.wav": [region(1, 1)]})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.status == "COMPLETED"
    assert response.totalSegments == 2
    assert response.totalChunks == 1
    assert response.processedChunks == 1
    assert len(provider.calls) == 1
    assert [item.segmentId for item in response.transcripts] == ["speech-segment"]


@pytest.mark.asyncio
async def test_toggle_off_uses_legacy_chunk_planning_without_calling_srd(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    provider = FakeProvider()
    srd = FailsIfCalledSpeechRegionService()
    chunks = TrackingAudioChunkService(chunk_seconds=180)

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
        speech_region_enabled=False,
    ).transcribe_manifest(TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"))

    assert response.status == "COMPLETED"
    assert response.totalChunks == 1
    assert srd.calls == []
    assert len(chunks.plan_chunks_calls) == 1
    assert chunks.plan_speech_regions_calls == []


@pytest.mark.asyncio
async def test_srd_detection_error_falls_back_to_legacy_chunking(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": SpeechRegionDetectionError("vad failed")})
    chunks = TrackingAudioChunkService(chunk_seconds=180)

    with caplog.at_level(logging.WARNING):
        response = await make_service(
            root,
            provider=provider,
            speech_region_service=srd,
            audio_chunk_service=chunks,
        ).transcribe_manifest(TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"))

    assert response.status == "COMPLETED"
    assert response.totalChunks == 1
    assert len(chunks.plan_chunks_calls) == 1
    assert any("falling back to legacy STT chunk planning" in record.message for record in caplog.records)


@pytest.mark.asyncio
async def test_unexpected_srd_runtime_error_falls_back_to_legacy_chunking(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": RuntimeError("surprise")})
    chunks = TrackingAudioChunkService(chunk_seconds=180)

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
    ).transcribe_manifest(TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"))

    assert response.status == "COMPLETED"
    assert response.totalChunks == 1
    assert len(chunks.plan_chunks_calls) == 1
    assert len(provider.calls) == 1


@pytest.mark.asyncio
async def test_speech_region_planning_error_falls_back_to_legacy_chunking(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=3)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": [region(1, 1)]})
    chunks = TrackingAudioChunkService(chunk_seconds=180, plan_speech_error=ValueError("bad regions"))

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
    ).transcribe_manifest(TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"))

    assert response.status == "COMPLETED"
    assert response.totalChunks == 1
    assert len(chunks.plan_speech_regions_calls) == 1
    assert len(chunks.plan_chunks_calls) == 1


@pytest.mark.asyncio
async def test_direct_manifest_propagates_error_when_srd_and_legacy_planning_both_fail(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    srd = FakeSpeechRegionService({"speech.wav": SpeechRegionDetectionError("vad failed")})
    chunks = TrackingAudioChunkService(chunk_seconds=180, legacy_error=ValueError("legacy failed"))

    with pytest.raises(ValueError, match="legacy failed"):
        await make_service(root, speech_region_service=srd, audio_chunk_service=chunks).transcribe_manifest(
            TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
        )


@pytest.mark.asyncio
async def test_meeting_records_planning_failure_when_srd_and_legacy_planning_both_fail(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": SpeechRegionDetectionError("vad failed")})
    chunks = TrackingAudioChunkService(chunk_seconds=180, legacy_error=ValueError("legacy failed"))

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        audio_chunk_service=chunks,
    ).transcribe_meeting(
        TranscribeMeetingRequest(
            requestId="planning-failure",
            meetingId=12,
            manifestPaths=["meeting-12/session-abc/manifest.json"],
        )
    )

    assert response.status == "FAILED"
    assert response.totalSegments == 1
    assert response.totalChunks == 1
    assert response.processedChunks == 1
    assert response.failedChunks == 1
    assert response.transcripts == []
    assert provider.calls == []
    assert response.failures[0].segmentId == "segment-001"
    assert response.failures[0].error == "Unable to prepare audio chunk"


@pytest.mark.asyncio
async def test_srd_construction_failure_does_not_prevent_service_construction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import app.services.manifest_transcription_service as manifest_module

    class RaisingSpeechRegionService:
        def __init__(self) -> None:
            raise RuntimeError("vad unavailable")

    monkeypatch.setattr(manifest_module, "SpeechRegionService", RaisingSpeechRegionService)
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=1)
    provider = FakeProvider()
    chunks = TrackingAudioChunkService(chunk_seconds=180)
    service = make_service(root, provider=provider, audio_chunk_service=chunks)

    response = await service.transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert service.speech_region_service is None
    assert response.status == "COMPLETED"
    assert response.totalChunks == 1
    assert len(chunks.plan_chunks_calls) == 1
    assert chunks.plan_speech_regions_calls == []


@pytest.mark.asyncio
async def test_progress_counts_filtered_chunks_only(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=5)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": [region(1, 1), region(3, 1)]})
    progress: list[tuple[int, int, float]] = []

    async def record_progress(processed: int, total: int, percent: float) -> None:
        progress.append((processed, total, percent))

    response = await make_service(
        root,
        provider=provider,
        speech_region_service=srd,
        concurrency=1,
    ).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json"),
        progress_callback=record_progress,
    )

    assert response.totalSegments == 1
    assert response.totalChunks == 2
    assert response.processedChunks == 2
    assert progress == [(0, 2, 0.0), (1, 2, 50.0), (2, 2, 100.0)]


@pytest.mark.asyncio
async def test_temp_chunk_is_cleaned_after_success_and_source_hash_is_unchanged(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    wav_path = manifest_path.parent / file_path
    write_wav(wav_path, duration_seconds=3)
    original_hash = hash_file(wav_path)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": [region(1, 1)]})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.status == "COMPLETED"
    assert provider.exists_during_call == [True]
    assert provider.calls[0].name == "chunk-001.wav"
    assert not provider.calls[0].exists()
    assert not list(wav_path.parent.glob("meeting-stt-chunks-*"))
    assert hash_file(wav_path) == original_hash


@pytest.mark.asyncio
async def test_temp_chunk_is_cleaned_after_stt_failure_and_source_hash_is_unchanged(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    wav_path = manifest_path.parent / file_path
    write_wav(wav_path, duration_seconds=3)
    original_hash = hash_file(wav_path)
    provider = FakeProvider(error=RuntimeError("stt failed"))
    srd = FakeSpeechRegionService({"speech.wav": [region(1, 1)]})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.status == "FAILED"
    assert response.totalChunks == 1
    assert response.failedChunks == 1
    assert provider.exists_during_call == [True]
    assert not provider.calls[0].exists()
    assert not list(wav_path.parent.glob("meeting-stt-chunks-*"))
    assert hash_file(wav_path) == original_hash


@pytest.mark.asyncio
async def test_total_segments_remains_one_when_one_segment_has_three_speech_chunks(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    file_path = "participants/attendee-1/speech.wav"
    manifest_path = write_manifest(root, single_segment_participants(file_path))
    write_wav(manifest_path.parent / file_path, duration_seconds=7)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"speech.wav": [region(0, 1), region(3, 1), region(6, 1)]})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.totalSegments == 1
    assert response.totalChunks == 3
    assert response.succeededChunks == 3


@pytest.mark.asyncio
async def test_all_silent_multi_segment_manifest_completes_without_stt_calls(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    participants = [
        {
            "attendeeId": "attendee-1",
            "displayName": "Speaker One",
            "segments": [
                segment("silent-1", 1, "participants/attendee-1/silent-1.wav"),
                segment("silent-2", 2, "participants/attendee-1/silent-2.wav"),
            ],
        }
    ]
    manifest_path = write_manifest(root, participants)
    write_wav(manifest_path.parent / "participants/attendee-1/silent-1.wav", duration_seconds=2)
    write_wav(manifest_path.parent / "participants/attendee-1/silent-2.wav", duration_seconds=2)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"silent-1.wav": [], "silent-2.wav": []})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_manifest(
        TranscribeManifestRequest(meetingId=12, manifestPath="meeting-12/session-abc/manifest.json")
    )

    assert response.status == "COMPLETED"
    assert response.totalSegments == 2
    assert response.totalChunks == 0
    assert response.processedChunks == 0
    assert response.transcripts == []
    assert provider.calls == []


@pytest.mark.asyncio
async def test_transcribe_meeting_two_silent_manifests_completes_without_chunks(tmp_path: Path) -> None:
    root = tmp_path / "meetings"
    manifest_a = write_manifest(
        root,
        single_segment_participants("participants/attendee-a/silent-a.wav", "silent-a"),
        session_id="session-a",
    )
    manifest_b = write_manifest(
        root,
        single_segment_participants("participants/attendee-b/silent-b.wav", "silent-b"),
        session_id="session-b",
    )
    write_wav(manifest_a.parent / "participants/attendee-a/silent-a.wav", duration_seconds=2)
    write_wav(manifest_b.parent / "participants/attendee-b/silent-b.wav", duration_seconds=2)
    provider = FakeProvider()
    srd = FakeSpeechRegionService({"silent-a.wav": [], "silent-b.wav": []})

    response = await make_service(root, provider=provider, speech_region_service=srd).transcribe_meeting(
        TranscribeMeetingRequest(
            requestId="all-silent",
            meetingId=12,
            manifestPaths=[
                "meeting-12/session-a/manifest.json",
                "meeting-12/session-b/manifest.json",
            ],
        )
    )

    assert response.status == "COMPLETED"
    assert response.totalManifests == 2
    assert response.totalSegments == 2
    assert response.totalChunks == 0
    assert response.processedChunks == 0
    assert response.sessionIds == ["session-a", "session-b"]
    assert response.transcripts == []
    assert provider.calls == []
