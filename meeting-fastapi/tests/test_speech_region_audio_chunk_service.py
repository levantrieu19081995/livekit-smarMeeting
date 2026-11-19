import hashlib
import wave
from pathlib import Path

import pytest

from app.services.audio_chunk_service import AudioChunkService
from app.services.speech_region_service import SpeechRegion


SAMPLE_RATE = 48000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2


def frames(seconds: int) -> int:
    return SAMPLE_RATE * seconds


def region(start_seconds: int, duration_seconds: int) -> SpeechRegion:
    return SpeechRegion(
        startFrame=frames(start_seconds),
        frameCount=frames(duration_seconds),
        startMs=start_seconds * 1000,
        durationMs=duration_seconds * 1000,
    )


def write_wav(path: Path, duration_seconds: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_frames = frames(duration_seconds)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(CHANNELS)
        wav_file.setsampwidth(SAMPLE_WIDTH_BYTES)
        wav_file.setframerate(SAMPLE_RATE)
        for start_frame in range(0, total_frames, SAMPLE_RATE):
            frame_count = min(SAMPLE_RATE, total_frames - start_frame)
            wav_file.writeframes(pcm_frames(start_frame, frame_count))


def pcm_frames(start_frame: int, frame_count: int) -> bytes:
    data = bytearray(frame_count * SAMPLE_WIDTH_BYTES)
    for offset in range(frame_count):
        sample = ((start_frame + offset) // SAMPLE_RATE * 1000) - 12000
        data[offset * 2 : offset * 2 + 2] = sample.to_bytes(2, "little", signed=True)
    return bytes(data)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wav_metadata(path: Path) -> tuple[int, int, int, int]:
    with wave.open(str(path), "rb") as wav_file:
        return (
            wav_file.getnchannels(),
            wav_file.getsampwidth(),
            wav_file.getframerate(),
            wav_file.getnframes(),
        )


def read_frames(path: Path, start_frame: int, frame_count: int) -> bytes:
    with wave.open(str(path), "rb") as wav_file:
        wav_file.setpos(start_frame)
        return wav_file.readframes(frame_count)


def test_empty_regions_return_no_plans_and_preserve_raw(tmp_path: Path) -> None:
    wav_path = tmp_path / "raw.wav"
    write_wav(wav_path, 2)
    raw_hash_before = sha256(wav_path)

    plans = AudioChunkService(chunk_seconds=180).plan_speech_regions(wav_path, [])

    assert plans == []
    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before


def test_single_short_region_uses_raw_frames_and_forces_temp_plan(tmp_path: Path) -> None:
    wav_path = tmp_path / "raw.wav"
    write_wav(wav_path, 5)

    plans = AudioChunkService(chunk_seconds=180).plan_speech_regions(wav_path, [region(1, 1)])

    assert len(plans) == 1
    assert plans[0].chunkIndex == 1
    assert plans[0].startFrame == frames(1)
    assert plans[0].frameCount == frames(1)
    assert plans[0].startMs == 1000
    assert plans[0].durationMs == 1000
    assert plans[0].useOriginal is False


def test_whole_short_wav_region_differs_from_legacy_whole_wav_plan(tmp_path: Path) -> None:
    wav_path = tmp_path / "short.wav"
    write_wav(wav_path, 2)
    service = AudioChunkService(chunk_seconds=180)

    legacy_plan = service.plan_chunks(wav_path)[0]
    speech_plan = service.plan_speech_regions(wav_path, [region(0, 2)])[0]

    assert legacy_plan.useOriginal is True
    assert speech_plan.useOriginal is False
    assert speech_plan.startFrame == 0
    assert speech_plan.frameCount == frames(2)


def test_two_distant_regions_stay_separate_without_concatenation(tmp_path: Path) -> None:
    wav_path = tmp_path / "distant.wav"
    write_wav(wav_path, 10)

    plans = AudioChunkService(chunk_seconds=180).plan_speech_regions(
        wav_path,
        [region(1, 1), region(8, 1)],
    )

    assert [plan.chunkIndex for plan in plans] == [1, 2]
    assert [plan.startMs for plan in plans] == [1000, 8000]
    assert [plan.durationMs for plan in plans] == [1000, 1000]
    assert [plan.startFrame for plan in plans] == [frames(1), frames(8)]
    assert [plan.frameCount for plan in plans] == [frames(1), frames(1)]


def test_long_region_splits_by_chunk_seconds(tmp_path: Path) -> None:
    wav_path = tmp_path / "long-region.wav"
    write_wav(wav_path, 10)

    plans = AudioChunkService(chunk_seconds=2).plan_speech_regions(wav_path, [region(1, 5)])

    assert [plan.chunkIndex for plan in plans] == [1, 2, 3]
    assert [plan.startMs for plan in plans] == [1000, 3000, 5000]
    assert [plan.durationMs for plan in plans] == [2000, 2000, 1000]
    assert all(plan.useOriginal is False for plan in plans)


def test_long_region_with_non_zero_offset_preserves_raw_offsets(tmp_path: Path) -> None:
    wav_path = tmp_path / "offset.wav"
    write_wav(wav_path, 12)

    plans = AudioChunkService(chunk_seconds=2).plan_speech_regions(wav_path, [region(5, 5)])

    assert [plan.startMs for plan in plans] == [5000, 7000, 9000]
    assert [plan.durationMs for plan in plans] == [2000, 2000, 1000]
    assert [plan.startFrame for plan in plans] == [frames(5), frames(7), frames(9)]


def test_multiple_regions_and_split_use_global_chunk_index(tmp_path: Path) -> None:
    wav_path = tmp_path / "global-index.wav"
    write_wav(wav_path, 10)

    plans = AudioChunkService(chunk_seconds=2).plan_speech_regions(
        wav_path,
        [region(1, 5), region(8, 1)],
    )

    assert [plan.chunkIndex for plan in plans] == [1, 2, 3, 4]
    assert plans[3].startMs == 8000
    assert plans[3].durationMs == 1000


def test_invalid_negative_start_is_rejected_without_modifying_raw(tmp_path: Path) -> None:
    wav_path = tmp_path / "negative.wav"
    write_wav(wav_path, 2)
    raw_hash_before = sha256(wav_path)
    bad_region = SpeechRegion(startFrame=-1, frameCount=frames(1), startMs=-1, durationMs=1000)

    with pytest.raises(ValueError, match="startFrame"):
        AudioChunkService(chunk_seconds=2).plan_speech_regions(wav_path, [bad_region])

    assert sha256(wav_path) == raw_hash_before


@pytest.mark.parametrize("frame_count", [0, -1])
def test_zero_or_negative_frame_count_is_rejected(tmp_path: Path, frame_count: int) -> None:
    wav_path = tmp_path / "bad-count.wav"
    write_wav(wav_path, 2)
    bad_region = SpeechRegion(startFrame=0, frameCount=frame_count, startMs=0, durationMs=0)

    with pytest.raises(ValueError, match="frameCount"):
        AudioChunkService(chunk_seconds=2).plan_speech_regions(wav_path, [bad_region])


def test_region_past_eof_is_rejected_without_clamping(tmp_path: Path) -> None:
    wav_path = tmp_path / "past-eof.wav"
    write_wav(wav_path, 2)

    with pytest.raises(ValueError, match="beyond"):
        AudioChunkService(chunk_seconds=2).plan_speech_regions(wav_path, [region(1, 2)])


def test_overlapping_regions_are_rejected(tmp_path: Path) -> None:
    wav_path = tmp_path / "overlap.wav"
    write_wav(wav_path, 5)

    with pytest.raises(ValueError, match="ordered and non-overlapping"):
        AudioChunkService(chunk_seconds=2).plan_speech_regions(
            wav_path,
            [region(1, 2), region(2, 2)],
        )


def test_out_of_order_regions_are_rejected_without_sorting(tmp_path: Path) -> None:
    wav_path = tmp_path / "out-of-order.wav"
    write_wav(wav_path, 10)

    with pytest.raises(ValueError, match="ordered and non-overlapping"):
        AudioChunkService(chunk_seconds=2).plan_speech_regions(
            wav_path,
            [region(8, 1), region(1, 1)],
        )


def test_materialized_speech_region_temp_wav_contains_exact_pcm_range(tmp_path: Path) -> None:
    wav_path = tmp_path / "pcm-range.wav"
    write_wav(wav_path, 3)
    service = AudioChunkService(chunk_seconds=180)
    plan = service.plan_speech_regions(wav_path, [region(1, 1)])[0]

    chunk = service.materialize_chunk(plan)

    try:
        assert chunk.temporary is True
        assert chunk.startMs == 1000
        assert chunk.durationMs == 1000
        assert wav_metadata(chunk.path) == (CHANNELS, SAMPLE_WIDTH_BYTES, SAMPLE_RATE, frames(1))
        assert read_frames(chunk.path, 0, frames(1)) == read_frames(wav_path, frames(1), frames(1))
        assert read_frames(chunk.path, 0, 100) != read_frames(wav_path, 0, 100)
        assert read_frames(chunk.path, frames(1) - 100, 100) != read_frames(wav_path, frames(2), 100)
    finally:
        service.cleanup_chunk(chunk)


def test_cleanup_speech_region_temp_preserves_raw_file_bytes_and_metadata(tmp_path: Path) -> None:
    wav_path = tmp_path / "cleanup.wav"
    write_wav(wav_path, 5)
    raw_hash_before = sha256(wav_path)
    raw_metadata_before = wav_metadata(wav_path)
    service = AudioChunkService(chunk_seconds=180)
    chunk = service.materialize_chunk(service.plan_speech_regions(wav_path, [region(2, 1)])[0])
    temp_dir = chunk.path.parent

    assert chunk.path.exists()
    service.cleanup_chunk(chunk)

    assert not chunk.path.exists()
    assert not temp_dir.exists()
    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before
    assert wav_metadata(wav_path) == raw_metadata_before


def test_simulated_downstream_failure_can_cleanup_temp_and_preserve_raw(tmp_path: Path) -> None:
    wav_path = tmp_path / "downstream-failure.wav"
    write_wav(wav_path, 5)
    raw_hash_before = sha256(wav_path)
    service = AudioChunkService(chunk_seconds=180)
    chunk = service.materialize_chunk(service.plan_speech_regions(wav_path, [region(1, 1)])[0])
    temp_dir = chunk.path.parent

    with pytest.raises(RuntimeError, match="simulated downstream failure"):
        try:
            raise RuntimeError("simulated downstream failure")
        finally:
            service.cleanup_chunk(chunk)

    assert not chunk.path.exists()
    assert not temp_dir.exists()
    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before


def test_materialize_write_failure_removes_temp_artifacts_and_preserves_raw(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    wav_path = tmp_path / "materialize-failure.wav"
    write_wav(wav_path, 5)
    raw_hash_before = sha256(wav_path)
    service = AudioChunkService(chunk_seconds=180)
    plan = service.plan_speech_regions(wav_path, [region(1, 1)])[0]
    real_wave_open = wave.open

    def failing_wave_open(path: str, mode: str = "rb"):
        if mode == "wb":
            Path(path).write_bytes(b"partial")
            raise RuntimeError("simulated output write failure")
        return real_wave_open(path, mode)

    monkeypatch.setattr("app.services.audio_chunk_service.wave.open", failing_wave_open)

    with pytest.raises(RuntimeError, match="simulated output write failure"):
        service.materialize_chunk(plan)

    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before
    assert not list(tmp_path.glob("meeting-stt-chunks-*"))


def test_legacy_short_wav_plan_chunks_still_uses_original(tmp_path: Path) -> None:
    wav_path = tmp_path / "legacy-short.wav"
    write_wav(wav_path, 2)

    plans = AudioChunkService(chunk_seconds=180).plan_chunks(wav_path)

    assert len(plans) == 1
    assert plans[0].useOriginal is True
