import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from app.core.blocking import check_cancelled


SUPPORTED_SAMPLE_RATES = {8000, 16000, 32000, 48000}
SUPPORTED_FRAME_MS = {10, 20, 30}
PCM16_SAMPLE_WIDTH_BYTES = 2
MONO_CHANNELS = 1


class SpeechRegionDetectionError(Exception):
    """Raised when speech region detection cannot safely process a WAV."""


@dataclass(frozen=True)
class SpeechRegion:
    startFrame: int
    frameCount: int
    startMs: int
    durationMs: int

    @property
    def endFrame(self) -> int:
        return self.startFrame + self.frameCount

    @property
    def endMs(self) -> int:
        return self.startMs + self.durationMs


@dataclass(frozen=True)
class SpeechRegionOptions:
    vad_mode: int = 1
    frame_ms: int = 20
    pre_padding_ms: int = 350
    post_padding_ms: int = 500
    merge_gap_ms: int = 700
    min_speech_ms: int = 60


class FrameVoiceActivityDetector(Protocol):
    def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
        ...


class WebRtcVadFrameDetector:
    def __init__(self, vad_mode: int) -> None:
        try:
            import webrtcvad

            self._vad = webrtcvad.Vad(vad_mode)
        except Exception as exc:
            raise SpeechRegionDetectionError("Unable to initialize WebRTC VAD") from exc

    def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
        try:
            return bool(self._vad.is_speech(frame_bytes, sample_rate))
        except Exception as exc:
            raise SpeechRegionDetectionError("WebRTC VAD failed to process frame") from exc


class SpeechRegionService:
    def __init__(
        self,
        options: SpeechRegionOptions | None = None,
        detector: FrameVoiceActivityDetector | None = None,
    ) -> None:
        self.options = options or SpeechRegionOptions()
        self._validate_options(self.options)
        self.detector = detector

    def detect(self, wav_path: Path) -> list[SpeechRegion]:
        wav_path = Path(wav_path)
        if not wav_path.exists():
            raise SpeechRegionDetectionError(f"WAV file not found: {wav_path}")
        if not wav_path.is_file():
            raise SpeechRegionDetectionError(f"WAV path is not a file: {wav_path}")

        try:
            detector = self.detector or WebRtcVadFrameDetector(self.options.vad_mode)
            with wave.open(str(wav_path), "rb") as wav_file:
                sample_rate = wav_file.getframerate()
                channels = wav_file.getnchannels()
                sample_width = wav_file.getsampwidth()
                total_frames = wav_file.getnframes()
                self._validate_wav_format(channels, sample_width, sample_rate)

                frame_samples = self._frame_samples(sample_rate, self.options.frame_ms)
                frame_bytes_length = frame_samples * sample_width * channels
                spans = self._collect_voiced_spans(
                    wav_file=wav_file,
                    sample_rate=sample_rate,
                    total_frames=total_frames,
                    frame_samples=frame_samples,
                    frame_bytes_length=frame_bytes_length,
                    detector=detector,
                )
        except SpeechRegionDetectionError:
            raise
        except (OSError, EOFError, wave.Error) as exc:
            raise SpeechRegionDetectionError(f"Unable to read WAV file: {wav_path}") from exc

        merged = self._merge_spans(
            spans,
            max_gap_frames=self._ms_to_frames(self.options.merge_gap_ms, sample_rate),
        )
        filtered = [
            span
            for span in merged
            if span[1] - span[0] >= self._ms_to_frames(self.options.min_speech_ms, sample_rate)
        ]
        padded = self._pad_spans(
            filtered,
            total_frames=total_frames,
            pre_padding_frames=self._ms_to_frames(self.options.pre_padding_ms, sample_rate),
            post_padding_frames=self._ms_to_frames(self.options.post_padding_ms, sample_rate),
        )
        final_spans = self._merge_spans(padded, max_gap_frames=0)
        return [
            SpeechRegion(
                startFrame=start_frame,
                frameCount=end_frame - start_frame,
                startMs=self._duration_ms(start_frame, sample_rate),
                durationMs=self._duration_ms(end_frame - start_frame, sample_rate),
            )
            for start_frame, end_frame in final_spans
            if end_frame > start_frame
        ]

    def _collect_voiced_spans(
        self,
        *,
        wav_file: wave.Wave_read,
        sample_rate: int,
        total_frames: int,
        frame_samples: int,
        frame_bytes_length: int,
        detector: FrameVoiceActivityDetector,
    ) -> list[tuple[int, int]]:
        spans: list[tuple[int, int]] = []
        active_start: int | None = None
        current_frame = 0

        while current_frame < total_frames:
            check_cancelled()
            frame_bytes = wav_file.readframes(frame_samples)
            if not frame_bytes:
                break
            actual_frames = len(frame_bytes) // PCM16_SAMPLE_WIDTH_BYTES
            if actual_frames <= 0:
                break
            detector_frame = frame_bytes
            if len(detector_frame) < frame_bytes_length:
                detector_frame = detector_frame + (b"\x00" * (frame_bytes_length - len(detector_frame)))

            try:
                is_speech = detector.is_speech(detector_frame, sample_rate)
            except SpeechRegionDetectionError:
                raise
            except Exception as exc:
                raise SpeechRegionDetectionError("Voice activity detector failed") from exc

            if is_speech and active_start is None:
                active_start = current_frame
            elif not is_speech and active_start is not None:
                spans.append((active_start, current_frame))
                active_start = None

            current_frame += actual_frames

        if active_start is not None:
            spans.append((active_start, total_frames))

        return spans

    def _pad_spans(
        self,
        spans: list[tuple[int, int]],
        *,
        total_frames: int,
        pre_padding_frames: int,
        post_padding_frames: int,
    ) -> list[tuple[int, int]]:
        return [
            (
                max(0, start_frame - pre_padding_frames),
                min(total_frames, end_frame + post_padding_frames),
            )
            for start_frame, end_frame in spans
        ]

    def _merge_spans(
        self,
        spans: list[tuple[int, int]],
        *,
        max_gap_frames: int,
    ) -> list[tuple[int, int]]:
        if not spans:
            return []

        ordered = sorted(spans)
        merged: list[tuple[int, int]] = []
        current_start, current_end = ordered[0]

        for start_frame, end_frame in ordered[1:]:
            if start_frame - current_end <= max_gap_frames:
                current_end = max(current_end, end_frame)
                continue
            if current_end > current_start:
                merged.append((current_start, current_end))
            current_start, current_end = start_frame, end_frame

        if current_end > current_start:
            merged.append((current_start, current_end))
        return merged

    def _validate_options(self, options: SpeechRegionOptions) -> None:
        if options.vad_mode not in {0, 1, 2, 3}:
            raise SpeechRegionDetectionError("vad_mode must be one of 0, 1, 2, 3")
        if options.frame_ms not in SUPPORTED_FRAME_MS:
            raise SpeechRegionDetectionError("frame_ms must be one of 10, 20, 30")
        for field_name in ("pre_padding_ms", "post_padding_ms", "merge_gap_ms", "min_speech_ms"):
            if getattr(options, field_name) < 0:
                raise SpeechRegionDetectionError(f"{field_name} must not be negative")

    def _validate_wav_format(self, channels: int, sample_width: int, sample_rate: int) -> None:
        if channels != MONO_CHANNELS:
            raise SpeechRegionDetectionError("Speech region detection requires mono WAV audio")
        if sample_width != PCM16_SAMPLE_WIDTH_BYTES:
            raise SpeechRegionDetectionError("Speech region detection requires 16-bit PCM WAV audio")
        if sample_rate not in SUPPORTED_SAMPLE_RATES:
            raise SpeechRegionDetectionError(
                "Speech region detection requires sample rate 8000, 16000, 32000, or 48000 Hz"
            )

    def _frame_samples(self, sample_rate: int, frame_ms: int) -> int:
        numerator = sample_rate * frame_ms
        if numerator % 1000 != 0:
            raise SpeechRegionDetectionError("Configured frame_ms does not produce whole PCM frames")
        return numerator // 1000

    def _ms_to_frames(self, milliseconds: int, sample_rate: int) -> int:
        return int(milliseconds * sample_rate / 1000)

    def _duration_ms(self, frames: int, sample_rate: int) -> int:
        return int(frames * 1000 / sample_rate)
