import asyncio
import wave
from pathlib import Path
from typing import Any, Callable

from app.models.manifest import SegmentMetadata
from app.services.capture_audio_stream import CaptureAudioStream, CaptureBufferOverflow
from app.utils.datetime_utils import iso_utc, utc_now


class SegmentRecorder:
    def __init__(self, *, track: Any, file_path: Path, segment: SegmentMetadata,
                 sample_rate: int, num_channels: int, sample_width_bytes: int,
                 stream_factory: Callable[..., Any] | None = None,
                 stream_capacity: int = 200, write_queue_capacity: int = 8,
                 batch_ms: int = 250, close_timeout: float = 5) -> None:
        self.track, self.file_path, self.segment = track, file_path, segment
        self.sample_rate, self.num_channels, self.sample_width_bytes = sample_rate, num_channels, sample_width_bytes
        self.stream_factory = stream_factory or CaptureAudioStream
        self.stream_capacity, self.write_queue_capacity = stream_capacity, write_queue_capacity
        self.batch_bytes = max(sample_width_bytes * num_channels, int(sample_rate * num_channels * sample_width_bytes * batch_ms / 1000))
        self.close_timeout = close_timeout

    def _open_wav(self):
        self.file_path.parent.mkdir(parents=True, exist_ok=True)
        wav = wave.open(str(self.file_path), "wb")
        try:
            wav.setnchannels(self.num_channels)
            wav.setsampwidth(self.sample_width_bytes)
            wav.setframerate(self.sample_rate)
            return wav
        except BaseException:
            wav.close()
            raise

    async def record(self) -> SegmentMetadata:
        started = utc_now()
        self.segment.startedAt = iso_utc(started)
        stream = None
        wav = None
        writer = None
        queue: asyncio.Queue[bytes | None] = asyncio.Queue(self.write_queue_capacity)
        cancelled = False
        buffer = bytearray()
        record_task = asyncio.current_task()
        producing = True

        async def write_batches():
            while True:
                data = await queue.get()
                try:
                    if data is None:
                        return
                    await asyncio.to_thread(wav.writeframesraw, data)
                    self.segment.byteCount += len(data)
                    self.segment.sampleCount += len(data) // (self.num_channels * self.sample_width_bytes)
                finally:
                    queue.task_done()

        def writer_finished(task):
            if producing and not task.cancelled() and task.exception() is not None and record_task is not None and not record_task.done():
                self.segment.status = "FAILED"
                self.segment.error = "CAPTURE_AUDIO_WRITE_FAILED"
                if not record_task.cancelling():
                    record_task.cancel()

        try:
            stream = self.stream_factory(track=self.track, sample_rate=self.sample_rate,
                                         num_channels=self.num_channels, capacity=self.stream_capacity)
            opening = asyncio.create_task(asyncio.to_thread(self._open_wav))
            try:
                wav = await asyncio.shield(opening)
            except asyncio.CancelledError:
                wav = await opening
                raise
            writer = asyncio.create_task(write_batches())
            writer.add_done_callback(writer_finished)
            async for event in stream:
                if writer.done():
                    await writer
                frame = getattr(event, "frame", event)
                data = frame.data.cast("B").tobytes()
                if len(data) % (self.num_channels * self.sample_width_bytes):
                    raise ValueError("Invalid PCM frame alignment")
                buffer.extend(data)
                self.segment.frameCount += 1
                if len(buffer) >= self.batch_bytes:
                    try:
                        queue.put_nowait(bytes(buffer))
                    except asyncio.QueueFull as exc:
                        raise CaptureBufferOverflow("CAPTURE_AUDIO_WRITE_BUFFER_OVERFLOW") from exc
                    buffer.clear()
        except asyncio.CancelledError:
            cancelled = self.segment.status != "FAILED"
            if cancelled:
                self.segment.status = "CANCELLED"
        except CaptureBufferOverflow:
            self.segment.status = "FAILED"
            self.segment.error = "CAPTURE_AUDIO_BUFFER_OVERFLOW"
        except Exception:
            self.segment.status = "FAILED"
            self.segment.error = "CAPTURE_AUDIO_WRITE_FAILED"
        finally:
            producing = False
            if stream is not None and hasattr(stream, "aclose"):
                try:
                    await asyncio.wait_for(stream.aclose(), self.close_timeout)
                except Exception:
                    self.segment.status = "FAILED"
                    self.segment.error = "CAPTURE_AUDIO_CLOSE_FAILED"
            if writer is not None:
                try:
                    if not writer.done():
                        async def enqueue(data):
                            put = asyncio.create_task(queue.put(data))
                            done, _ = await asyncio.wait({put, writer}, return_when=asyncio.FIRST_COMPLETED)
                            if writer in done:
                                put.cancel()
                                await asyncio.gather(put, return_exceptions=True)
                                await writer
                            else:
                                await put
                        if buffer:
                            await enqueue(bytes(buffer))
                        await enqueue(None)
                    await asyncio.shield(writer)
                except Exception:
                    self.segment.status = "FAILED"
                    self.segment.error = "CAPTURE_AUDIO_WRITE_FAILED"
            if wav is not None:
                try:
                    await asyncio.to_thread(wav.close)
                except Exception:
                    self.segment.status = "FAILED"
                    self.segment.error = "CAPTURE_AUDIO_CLOSE_FAILED"
            ended = utc_now()
            self.segment.endedAt = iso_utc(ended)
            self.segment.durationMs = int((ended - started).total_seconds() * 1000)
            if self.segment.status == "RUNNING":
                self.segment.status = "COMPLETED"
        if cancelled and self.segment.status != "FAILED":
            raise asyncio.CancelledError
        return self.segment
