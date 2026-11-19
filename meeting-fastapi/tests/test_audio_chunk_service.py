import hashlib
import wave
from pathlib import Path

from app.services.audio_chunk_service import AudioChunkService


SAMPLE_RATE = 48000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2


def write_wav(path: Path, duration_seconds: int, framerate: int = SAMPLE_RATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_frames = framerate * duration_seconds
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(CHANNELS)
        wav_file.setsampwidth(SAMPLE_WIDTH_BYTES)
        wav_file.setframerate(framerate)
        for start in range(0, total_frames, framerate):
            frame_count = min(framerate, total_frames - start)
            wav_file.writeframes(_pcm_frames(start, frame_count))


def _pcm_frames(start_frame: int, frame_count: int) -> bytes:
    data = bytearray(frame_count * SAMPLE_WIDTH_BYTES)
    for offset in range(frame_count):
        sample = ((start_frame + offset) % 32768) - 16384
        data[offset * 2 : offset * 2 + 2] = sample.to_bytes(2, "little", signed=True)
    return bytes(data)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_frames(path: Path, start_frame: int, frame_count: int) -> bytes:
    with wave.open(str(path), "rb") as wav_file:
        wav_file.setpos(start_frame)
        return wav_file.readframes(frame_count)


def test_short_wav_plan_uses_original_without_materializing(tmp_path: Path) -> None:
    wav_path = tmp_path / "short.wav"
    write_wav(wav_path, duration_seconds=10)

    plans = AudioChunkService(chunk_seconds=180).plan_chunks(wav_path)

    assert len(plans) == 1
    plan = plans[0]
    assert plan.sourcePath == wav_path
    assert plan.chunkIndex == 1
    assert plan.startFrame == 0
    assert plan.frameCount == SAMPLE_RATE * 10
    assert plan.startMs == 0
    assert plan.durationMs == 10000
    assert plan.useOriginal is True
    assert not list(tmp_path.glob("meeting-stt-chunks-*"))


def test_long_wav_plans_are_ordered_non_overlapping_raw_ranges(tmp_path: Path) -> None:
    wav_path = tmp_path / "long.wav"
    write_wav(wav_path, duration_seconds=5)

    plans = AudioChunkService(chunk_seconds=2).plan_chunks(wav_path)

    assert len(plans) == 3
    assert [plan.chunkIndex for plan in plans] == [1, 2, 3]
    assert [plan.startMs for plan in plans] == [0, 2000, 4000]
    assert [plan.durationMs for plan in plans] == [2000, 2000, 1000]
    assert [plan.startFrame for plan in plans] == [0, SAMPLE_RATE * 2, SAMPLE_RATE * 4]
    assert [plan.frameCount for plan in plans] == [SAMPLE_RATE * 2, SAMPLE_RATE * 2, SAMPLE_RATE]
    assert all(plan.useOriginal is False for plan in plans)

    previous_end = 0
    for plan in plans:
        assert plan.startFrame == previous_end
        previous_end = plan.startFrame + plan.frameCount
    assert previous_end == SAMPLE_RATE * 5


def test_materialize_sub_chunk_preserves_format_duration_and_source_frames(tmp_path: Path) -> None:
    wav_path = tmp_path / "source.wav"
    write_wav(wav_path, duration_seconds=5)
    source_hash_before = file_sha256(wav_path)
    service = AudioChunkService(chunk_seconds=2)
    plan = service.plan_chunks(wav_path)[1]

    chunk = service.materialize_chunk(plan)

    assert chunk.temporary is True
    assert chunk.path.exists()
    assert chunk.path != wav_path
    assert chunk.chunkIndex == 2
    assert chunk.startMs == 2000
    assert chunk.durationMs == 2000
    with wave.open(str(chunk.path), "rb") as chunk_file:
        assert chunk_file.getnchannels() == CHANNELS
        assert chunk_file.getsampwidth() == SAMPLE_WIDTH_BYTES
        assert chunk_file.getframerate() == SAMPLE_RATE
        assert chunk_file.getnframes() == SAMPLE_RATE * 2
        chunk_frames = chunk_file.readframes(chunk_file.getnframes())
    assert chunk_frames == read_frames(wav_path, plan.startFrame, plan.frameCount)
    assert wav_path.exists()
    assert file_sha256(wav_path) == source_hash_before

    service.cleanup_chunk(chunk)


def test_cleanup_temp_wav_deletes_temp_and_preserves_raw_bytes(tmp_path: Path) -> None:
    wav_path = tmp_path / "source.wav"
    write_wav(wav_path, duration_seconds=5)
    raw_bytes_before = wav_path.read_bytes()
    service = AudioChunkService(chunk_seconds=2)
    chunk = service.materialize_chunk(service.plan_chunks(wav_path)[0])
    temp_dir = chunk.path.parent

    service.cleanup_chunk(chunk)

    assert not chunk.path.exists()
    assert not temp_dir.exists()
    assert wav_path.exists()
    assert wav_path.read_bytes() == raw_bytes_before


def test_cleanup_original_plan_does_not_delete_or_modify_raw_wav(tmp_path: Path) -> None:
    wav_path = tmp_path / "short.wav"
    write_wav(wav_path, duration_seconds=10)
    raw_hash_before = file_sha256(wav_path)
    service = AudioChunkService(chunk_seconds=180)
    plan = service.plan_chunks(wav_path)[0]

    chunk = service.materialize_chunk(plan)
    service.cleanup_chunk(chunk)

    assert plan.useOriginal is True
    assert chunk.path == wav_path
    assert chunk.temporary is False
    assert wav_path.exists()
    assert file_sha256(wav_path) == raw_hash_before
