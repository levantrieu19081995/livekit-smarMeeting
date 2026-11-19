import uuid
import wave
from dataclasses import dataclass
from pathlib import Path

from app.services.speech_region_service import SpeechRegion
from app.core.blocking import check_cancelled


@dataclass
class AudioChunk:
    path: Path
    chunkIndex: int
    startMs: int
    durationMs: int
    temporary: bool


@dataclass
class AudioChunkPlan:
    sourcePath: Path
    chunkIndex: int
    startFrame: int
    frameCount: int
    startMs: int
    durationMs: int
    useOriginal: bool


class AudioChunkService:
    def __init__(self, chunk_seconds: int, max_audio_bytes: int = 24000000) -> None:
        self.chunk_seconds = chunk_seconds
        self.max_audio_bytes = max_audio_bytes

    def create_chunks(self, wav_path: Path) -> list[AudioChunk]:
        plans = self.plan_chunks(wav_path)
        chunks = []
        try:
            for plan in plans:
                chunks.append(self.materialize_chunk(plan))
            return chunks
        except BaseException:
            self.cleanup_chunks(chunks)
            raise

    def plan_chunks(self, wav_path: Path) -> list[AudioChunkPlan]:
        with wave.open(str(wav_path), "rb") as wav_file:
            framerate = wav_file.getframerate()
            total_frames = wav_file.getnframes()
            chunk_frames = min(framerate * self.chunk_seconds, (self.max_audio_bytes - 64) // (wav_file.getnchannels() * wav_file.getsampwidth()))
            if chunk_frames <= 0: raise ValueError("Audio chunk size must be positive")
            duration_ms = self._duration_ms(total_frames, framerate)

            if total_frames <= chunk_frames:
                return [
                    AudioChunkPlan(
                        sourcePath=wav_path,
                        chunkIndex=1,
                        startFrame=0,
                        frameCount=total_frames,
                        startMs=0,
                        durationMs=duration_ms,
                        useOriginal=True,
                    )
                ]

            plans: list[AudioChunkPlan] = []
            chunk_index = 1
            start_frame = 0

            while start_frame < total_frames:
                check_cancelled()
                frame_count = min(chunk_frames, total_frames - start_frame)
                plans.append(
                    AudioChunkPlan(
                        sourcePath=wav_path,
                        chunkIndex=chunk_index,
                        startFrame=start_frame,
                        frameCount=frame_count,
                        startMs=self._duration_ms(start_frame, framerate),
                        durationMs=self._duration_ms(frame_count, framerate),
                        useOriginal=False,
                    )
                )
                start_frame += frame_count
                chunk_index += 1

            return plans

    def plan_speech_regions(self, wav_path: Path, regions: list[SpeechRegion]) -> list[AudioChunkPlan]:
        if not regions:
            return []

        with wave.open(str(wav_path), "rb") as wav_file:
            framerate = wav_file.getframerate()
            total_frames = wav_file.getnframes()
            chunk_frames = min(framerate * self.chunk_seconds, (self.max_audio_bytes - 64) // (wav_file.getnchannels() * wav_file.getsampwidth()))

        if chunk_frames <= 0:
            raise ValueError("chunk_seconds must produce a positive frame count")

        self._validate_speech_regions(regions, total_frames)

        plans: list[AudioChunkPlan] = []
        chunk_index = 1
        for region in regions:
            remaining_frames = region.frameCount
            start_frame = region.startFrame
            while remaining_frames > 0:
                check_cancelled()
                frame_count = min(chunk_frames, remaining_frames)
                plans.append(
                    AudioChunkPlan(
                        sourcePath=wav_path,
                        chunkIndex=chunk_index,
                        startFrame=start_frame,
                        frameCount=frame_count,
                        startMs=self._duration_ms(start_frame, framerate),
                        durationMs=self._duration_ms(frame_count, framerate),
                        useOriginal=False,
                    )
                )
                chunk_index += 1
                start_frame += frame_count
                remaining_frames -= frame_count

        return plans

    def materialize_chunk(self, plan: AudioChunkPlan) -> AudioChunk:
        if plan.useOriginal:
            return AudioChunk(
                path=plan.sourcePath,
                chunkIndex=plan.chunkIndex,
                startMs=plan.startMs,
                durationMs=plan.durationMs,
                temporary=False,
            )

        with wave.open(str(plan.sourcePath), "rb") as source_file:
            channels = source_file.getnchannels()
            sample_width = source_file.getsampwidth()
            framerate = source_file.getframerate()
            source_file.setpos(plan.startFrame)
            frames = source_file.readframes(plan.frameCount)

        temp_dir = plan.sourcePath.parent / f"meeting-stt-chunks-{uuid.uuid4().hex}"
        temp_dir.mkdir(parents=True, exist_ok=False)
        chunk_path = temp_dir / f"chunk-{plan.chunkIndex:03d}.wav"
        try:
            with wave.open(str(chunk_path), "wb") as chunk_file:
                chunk_file.setnchannels(channels)
                chunk_file.setsampwidth(sample_width)
                chunk_file.setframerate(framerate)
                chunk_file.writeframes(frames)
        except Exception:
            self._cleanup_temp_path(chunk_path)
            raise

        return AudioChunk(
            path=chunk_path,
            chunkIndex=plan.chunkIndex,
            startMs=plan.startMs,
            durationMs=plan.durationMs,
            temporary=True,
        )

    def cleanup_chunk(self, chunk: AudioChunk) -> None:
        if not chunk.temporary:
            return
        temp_dir = chunk.path.parent
        try:
            chunk.path.unlink(missing_ok=True)
        except OSError:
            pass
        try:
            temp_dir.rmdir()
        except OSError:
            pass

    def cleanup_chunks(self, chunks: list[AudioChunk]) -> None:
        for chunk in chunks:
            self.cleanup_chunk(chunk)

    def _validate_speech_regions(self, regions: list[SpeechRegion], total_frames: int) -> None:
        previous_end = 0
        for index, region in enumerate(regions, start=1):
            if region.startFrame < 0:
                raise ValueError(f"Speech region {index} startFrame must not be negative")
            if region.frameCount <= 0:
                raise ValueError(f"Speech region {index} frameCount must be positive")
            end_frame = region.startFrame + region.frameCount
            if end_frame > total_frames:
                raise ValueError(f"Speech region {index} extends beyond source WAV")
            if region.startFrame < previous_end:
                raise ValueError("Speech regions must be ordered and non-overlapping")
            previous_end = end_frame

    def _cleanup_temp_path(self, chunk_path: Path) -> None:
        try:
            chunk_path.unlink(missing_ok=True)
        except OSError:
            pass
        try:
            chunk_path.parent.rmdir()
        except OSError:
            pass

    def _duration_ms(self, frames: int, framerate: int) -> int:
        return int(frames * 1000 / framerate)
