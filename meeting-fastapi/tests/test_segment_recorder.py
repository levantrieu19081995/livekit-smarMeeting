import asyncio
import uuid
import wave
from pathlib import Path

import pytest

from app.models.manifest import SegmentMetadata
from app.services.segment_recorder import SegmentRecorder


def work_dir() -> Path:
    path = Path("test_output") / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    return path


class FakeFrame:
    def __init__(self, data: bytes, samples: int) -> None:
        self.data = memoryview(data)
        self.samples_per_channel = samples


class FakeEvent:
    def __init__(self, frame: FakeFrame) -> None:
        self.frame = frame


class FakeStream:
    def __init__(self, frames, delay: float = 0) -> None:
        self.frames = frames
        self.delay = delay
        self.closed = False

    def __aiter__(self):
        self._iter = iter(self.frames)
        return self

    async def __anext__(self):
        await asyncio.sleep(self.delay)
        try:
            return FakeEvent(next(self._iter))
        except StopIteration:
            raise StopAsyncIteration

    async def aclose(self):
        self.closed = True


def segment(file_path: str = "a.wav") -> SegmentMetadata:
    return SegmentMetadata(
        segmentId="seg",
        segmentIndex=1,
        participantIdentity="p1",
        trackSid="TR_1",
        filePath=file_path,
        startedAt="now",
    )


@pytest.mark.asyncio
async def test_writes_pcm_frames_to_valid_wav() -> None:
    tmp_path = work_dir()
    frames = [FakeFrame(b"\x01\x00\x02\x00", 2), FakeFrame(b"\x03\x00\x04\x00", 2)]
    recorder = SegmentRecorder(
        track=object(),
        file_path=tmp_path / "one.wav",
        segment=segment(),
        sample_rate=48000,
        num_channels=1,
        sample_width_bytes=2,
        stream_factory=lambda **kwargs: FakeStream(frames),
    )
    result = await recorder.record()
    with wave.open(str(tmp_path / "one.wav"), "rb") as wav_file:
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getframerate() == 48000
        assert wav_file.getnframes() == 4
    assert result.frameCount == 2
    assert result.sampleCount == 4
    assert result.byteCount == 8


@pytest.mark.asyncio
async def test_recorder_closes_file_when_cancelled() -> None:
    tmp_path = work_dir()
    recorder = SegmentRecorder(
        track=object(),
        file_path=tmp_path / "cancel.wav",
        segment=segment(),
        sample_rate=48000,
        num_channels=1,
        sample_width_bytes=2,
        stream_factory=lambda **kwargs: FakeStream([FakeFrame(b"\x01\x00", 1)] * 10, delay=0.05),
    )
    task = asyncio.create_task(recorder.record())
    await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    with wave.open(str(tmp_path / "cancel.wav"), "rb") as wav_file:
        assert wav_file.getnchannels() == 1


@pytest.mark.asyncio
async def test_two_tracks_write_independent_files() -> None:
    tmp_path = work_dir()
    one = SegmentRecorder(
        track=object(),
        file_path=tmp_path / "one.wav",
        segment=segment("one.wav"),
        sample_rate=48000,
        num_channels=1,
        sample_width_bytes=2,
        stream_factory=lambda **kwargs: FakeStream([FakeFrame(b"\x01\x00", 1)]),
    )
    two = SegmentRecorder(
        track=object(),
        file_path=tmp_path / "two.wav",
        segment=segment("two.wav"),
        sample_rate=48000,
        num_channels=1,
        sample_width_bytes=2,
        stream_factory=lambda **kwargs: FakeStream([FakeFrame(b"\x02\x00", 1)]),
    )
    await asyncio.gather(one.record(), two.record())
    assert (tmp_path / "one.wav").read_bytes() != (tmp_path / "two.wav").read_bytes()
