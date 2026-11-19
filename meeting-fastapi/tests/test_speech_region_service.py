import hashlib
import wave
from pathlib import Path

import pytest

from app.services.speech_region_service import (
    SpeechRegionDetectionError,
    SpeechRegionOptions,
    SpeechRegionService,
    WebRtcVadFrameDetector,
)


SAMPLE_RATE = 48000
FRAME_MS = 20
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000


class FakeFrameDetector:
    def __init__(self, decisions: list[bool] | None = None) -> None:
        self.decisions = decisions or []
        self.calls: list[tuple[int, int]] = []

    def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
        self.calls.append((len(frame_bytes), sample_rate))
        if len(self.calls) <= len(self.decisions):
            return self.decisions[len(self.calls) - 1]
        return False


class FailingFrameDetector:
    def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
        raise RuntimeError("detector exploded")


def write_wav(
    path: Path,
    frame_count: int,
    *,
    channels: int = 1,
    sample_width: int = 2,
    sample_rate: int = SAMPLE_RATE,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(channels)
        wav_file.setsampwidth(sample_width)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00" * frame_count * channels * sample_width)


def write_vad_frames(path: Path, vad_frame_count: int) -> None:
    write_wav(path, vad_frame_count * FRAME_SAMPLES)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def region_tuples(regions: list) -> list[tuple[int, int, int, int]]:
    return [
        (region.startFrame, region.frameCount, region.startMs, region.durationMs)
        for region in regions
    ]


def detect_with_decisions(
    wav_path: Path,
    decisions: list[bool],
    *,
    options: SpeechRegionOptions | None = None,
) -> tuple[list, FakeFrameDetector]:
    detector = FakeFrameDetector(decisions)
    regions = SpeechRegionService(
        options=options or SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0),
        detector=detector,
    ).detect(wav_path)
    return regions, detector


def assert_ordered_non_overlapping(regions: list, total_frames: int) -> None:
    previous_end = 0
    for region in regions:
        assert region.startFrame >= previous_end
        assert region.frameCount > 0
        assert region.endFrame <= total_frames
        previous_end = region.endFrame


def test_webrtcvad_dependency_smoke() -> None:
    import webrtcvad

    vad = webrtcvad.Vad(1)
    silence_20ms_48khz_pcm16 = b"\x00\x00" * FRAME_SAMPLES

    assert vad.is_speech(silence_20ms_48khz_pcm16, SAMPLE_RATE) is False
    assert WebRtcVadFrameDetector(1).is_speech(silence_20ms_48khz_pcm16, SAMPLE_RATE) is False


def test_default_detector_is_fresh_per_detect(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    wav_a = tmp_path / "a.wav"
    wav_b = tmp_path / "b.wav"
    write_vad_frames(wav_a, 3)
    write_vad_frames(wav_b, 5)

    class RecordingDefaultDetector:
        instances: list["RecordingDefaultDetector"] = []

        def __init__(self, vad_mode: int) -> None:
            self.vad_mode = vad_mode
            self.calls: list[tuple[int, int]] = []
            RecordingDefaultDetector.instances.append(self)

        def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
            self.calls.append((len(frame_bytes), sample_rate))
            return False

    monkeypatch.setattr(
        "app.services.speech_region_service.WebRtcVadFrameDetector",
        RecordingDefaultDetector,
    )

    service = SpeechRegionService()

    assert service.detect(wav_a) == []
    assert service.detect(wav_b) == []
    assert len(RecordingDefaultDetector.instances) == 2
    assert RecordingDefaultDetector.instances[0] is not RecordingDefaultDetector.instances[1]
    assert RecordingDefaultDetector.instances[0].vad_mode == SpeechRegionOptions().vad_mode
    assert RecordingDefaultDetector.instances[1].vad_mode == SpeechRegionOptions().vad_mode
    assert len(RecordingDefaultDetector.instances[0].calls) == 3
    assert len(RecordingDefaultDetector.instances[1].calls) == 5


def test_repeated_same_wav_uses_fresh_default_detector_and_is_identical(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    wav_path = tmp_path / "repeat.wav"
    write_vad_frames(wav_path, 4)
    options = SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=0, min_speech_ms=20)

    class DeterministicDefaultDetector:
        instances: list["DeterministicDefaultDetector"] = []

        def __init__(self, vad_mode: int) -> None:
            self.calls = 0
            self.decisions = [False, True, True, False]
            DeterministicDefaultDetector.instances.append(self)

        def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
            decision = self.decisions[self.calls] if self.calls < len(self.decisions) else False
            self.calls += 1
            return decision

    monkeypatch.setattr(
        "app.services.speech_region_service.WebRtcVadFrameDetector",
        DeterministicDefaultDetector,
    )

    service = SpeechRegionService(options=options)

    first = region_tuples(service.detect(wav_path))
    second = region_tuples(service.detect(wav_path))

    assert len(DeterministicDefaultDetector.instances) == 2
    assert first == second
    assert first == [(FRAME_SAMPLES, FRAME_SAMPLES * 2, 20, 40)]


def test_default_detector_results_are_order_independent_with_stateful_detector(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    wav_a = tmp_path / "order-a.wav"
    wav_b = tmp_path / "order-b.wav"
    write_vad_frames(wav_a, 4)
    write_vad_frames(wav_b, 5)
    options = SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=0, min_speech_ms=20)

    class StatefulDefaultDetector:
        def __init__(self, vad_mode: int) -> None:
            self.calls = 0
            self.decisions = [False, True, True, False, False]

        def is_speech(self, frame_bytes: bytes, sample_rate: int) -> bool:
            decision = self.decisions[self.calls] if self.calls < len(self.decisions) else False
            self.calls += 1
            return decision

    monkeypatch.setattr(
        "app.services.speech_region_service.WebRtcVadFrameDetector",
        StatefulDefaultDetector,
    )

    fresh_a = region_tuples(SpeechRegionService(options=options).detect(wav_a))
    fresh_b = region_tuples(SpeechRegionService(options=options).detect(wav_b))

    same_service_ab = SpeechRegionService(options=options)
    same_a_first = region_tuples(same_service_ab.detect(wav_a))
    same_b_after_a = region_tuples(same_service_ab.detect(wav_b))

    same_service_ba = SpeechRegionService(options=options)
    same_b_first = region_tuples(same_service_ba.detect(wav_b))
    same_a_after_b = region_tuples(same_service_ba.detect(wav_a))

    assert same_a_first == fresh_a
    assert same_b_after_a == fresh_b
    assert same_b_first == fresh_b
    assert same_a_after_b == fresh_a


def test_injected_detector_is_caller_owned_and_default_detector_not_constructed(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    wav_path = tmp_path / "injected.wav"
    write_vad_frames(wav_path, 4)
    options = SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=0, min_speech_ms=20)
    constructed_modes: list[int] = []

    class UnexpectedDefaultDetector:
        def __init__(self, vad_mode: int) -> None:
            constructed_modes.append(vad_mode)
            raise AssertionError("default detector should not be constructed")

    monkeypatch.setattr(
        "app.services.speech_region_service.WebRtcVadFrameDetector",
        UnexpectedDefaultDetector,
    )

    detector = FakeFrameDetector([False, True, True, False])
    service = SpeechRegionService(options=options, detector=detector)
    regions = service.detect(wav_path)

    assert service.detector is detector
    assert constructed_modes == []
    assert detector.calls == [(FRAME_SAMPLES * 2, SAMPLE_RATE)] * 4
    assert region_tuples(regions) == [(FRAME_SAMPLES, FRAME_SAMPLES * 2, 20, 40)]


def test_default_detector_init_failure_is_raised_during_detect(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    wav_path = tmp_path / "vad-init-failure.wav"
    write_vad_frames(wav_path, 1)

    class FailingDefaultDetector:
        def __init__(self, vad_mode: int) -> None:
            raise SpeechRegionDetectionError("Unable to initialize WebRTC VAD")

    monkeypatch.setattr(
        "app.services.speech_region_service.WebRtcVadFrameDetector",
        FailingDefaultDetector,
    )

    service = SpeechRegionService()

    with pytest.raises(SpeechRegionDetectionError, match="Unable to initialize WebRTC VAD"):
        service.detect(wav_path)


def test_valid_silence_wav_returns_empty_and_preserves_raw(tmp_path: Path) -> None:
    wav_path = tmp_path / "silence.wav"
    write_vad_frames(wav_path, 10)
    raw_hash_before = sha256(wav_path)

    regions = SpeechRegionService(detector=FakeFrameDetector([False] * 10)).detect(wav_path)

    assert regions == []
    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before


def test_single_speech_region_with_padding_uses_raw_timeline(tmp_path: Path) -> None:
    wav_path = tmp_path / "single.wav"
    write_vad_frames(wav_path, 13)
    options = SpeechRegionOptions(
        pre_padding_ms=40,
        post_padding_ms=40,
        merge_gap_ms=0,
        min_speech_ms=20,
    )

    regions, detector = detect_with_decisions(
        wav_path,
        [False, False, False, False, True, True, True, True, True, False, False, False, False],
        options=options,
    )

    assert len(regions) == 1
    assert regions[0].startFrame == FRAME_SAMPLES * 2
    assert regions[0].frameCount == FRAME_SAMPLES * 9
    assert regions[0].startMs == 40
    assert regions[0].durationMs == 180
    assert detector.calls[0] == (FRAME_SAMPLES * 2, SAMPLE_RATE)


def test_short_meaningful_utterance_is_preserved_by_default_options(tmp_path: Path) -> None:
    wav_path = tmp_path / "short-meaningful.wav"
    write_vad_frames(wav_path, 30)

    regions, _ = detect_with_decisions(
        wav_path,
        [False] * 10 + [True, True, True] + [False] * 17,
        options=SpeechRegionOptions(),
    )

    assert len(regions) == 1
    assert regions[0].durationMs >= 60


def test_sub_threshold_noise_is_removed_without_merge_opportunity(tmp_path: Path) -> None:
    wav_path = tmp_path / "noise.wav"
    write_vad_frames(wav_path, 4)

    regions, _ = detect_with_decisions(
        wav_path,
        [False, True, True, False],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=0, min_speech_ms=60),
    )

    assert regions == []


def test_short_gap_is_merged_into_one_region(tmp_path: Path) -> None:
    wav_path = tmp_path / "short-gap.wav"
    write_vad_frames(wav_path, 5)

    regions, _ = detect_with_decisions(
        wav_path,
        [True, True, False, True, True],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=20, min_speech_ms=20),
    )

    assert len(regions) == 1
    assert regions[0].startFrame == 0
    assert regions[0].durationMs == 100


def test_long_gap_remains_two_ordered_regions(tmp_path: Path) -> None:
    wav_path = tmp_path / "long-gap.wav"
    write_vad_frames(wav_path, 7)

    regions, _ = detect_with_decisions(
        wav_path,
        [True, True, False, False, False, True, True],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=40, min_speech_ms=20),
    )

    assert len(regions) == 2
    assert [region.startMs for region in regions] == [0, 100]
    assert_ordered_non_overlapping(regions, FRAME_SAMPLES * 7)


def test_merge_happens_before_min_duration_filter(tmp_path: Path) -> None:
    wav_path = tmp_path / "merge-before-min.wav"
    write_vad_frames(wav_path, 5)

    regions, _ = detect_with_decisions(
        wav_path,
        [True, True, False, True, True],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=0, merge_gap_ms=20, min_speech_ms=80),
    )

    assert len(regions) == 1
    assert regions[0].durationMs == 100


def test_pre_padding_clamps_at_zero(tmp_path: Path) -> None:
    wav_path = tmp_path / "start.wav"
    write_vad_frames(wav_path, 4)

    regions, _ = detect_with_decisions(
        wav_path,
        [True, False, False, False],
        options=SpeechRegionOptions(pre_padding_ms=350, post_padding_ms=0, merge_gap_ms=0, min_speech_ms=20),
    )

    assert len(regions) == 1
    assert regions[0].startFrame == 0
    assert regions[0].startMs == 0


def test_post_padding_clamps_at_eof(tmp_path: Path) -> None:
    wav_path = tmp_path / "eof.wav"
    write_vad_frames(wav_path, 4)

    regions, _ = detect_with_decisions(
        wav_path,
        [False, False, False, True],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=500, merge_gap_ms=0, min_speech_ms=20),
    )

    assert len(regions) == 1
    assert regions[0].endFrame == FRAME_SAMPLES * 4
    assert regions[0].endMs == 80


def test_padding_overlap_merges_regions_after_padding(tmp_path: Path) -> None:
    wav_path = tmp_path / "padding-overlap.wav"
    write_vad_frames(wav_path, 7)

    regions, _ = detect_with_decisions(
        wav_path,
        [True, False, False, False, True, False, False],
        options=SpeechRegionOptions(pre_padding_ms=40, post_padding_ms=40, merge_gap_ms=0, min_speech_ms=20),
    )

    assert len(regions) == 1
    assert regions[0].startMs == 0
    assert regions[0].endMs == 140


def test_final_partial_frame_is_padded_for_detection_but_clamped_to_eof(tmp_path: Path) -> None:
    wav_path = tmp_path / "partial.wav"
    write_wav(wav_path, FRAME_SAMPLES * 2 + (FRAME_SAMPLES // 2))
    raw_hash_before = sha256(wav_path)

    regions, detector = detect_with_decisions(
        wav_path,
        [False, False, True],
        options=SpeechRegionOptions(pre_padding_ms=0, post_padding_ms=500, merge_gap_ms=0, min_speech_ms=1),
    )

    assert len(regions) == 1
    assert detector.calls[-1] == (FRAME_SAMPLES * 2, SAMPLE_RATE)
    assert regions[0].endFrame == FRAME_SAMPLES * 2 + (FRAME_SAMPLES // 2)
    assert regions[0].endMs == 50
    assert sha256(wav_path) == raw_hash_before


def test_unsupported_stereo_raises_controlled_error_and_preserves_raw(tmp_path: Path) -> None:
    wav_path = tmp_path / "stereo.wav"
    write_wav(wav_path, FRAME_SAMPLES, channels=2)
    raw_hash_before = sha256(wav_path)

    with pytest.raises(SpeechRegionDetectionError, match="mono"):
        SpeechRegionService(detector=FakeFrameDetector()).detect(wav_path)

    assert wav_path.exists()
    assert sha256(wav_path) == raw_hash_before


def test_unsupported_sample_width_raises_controlled_error(tmp_path: Path) -> None:
    wav_path = tmp_path / "pcm8.wav"
    write_wav(wav_path, FRAME_SAMPLES, sample_width=1)

    with pytest.raises(SpeechRegionDetectionError, match="16-bit"):
        SpeechRegionService(detector=FakeFrameDetector()).detect(wav_path)


def test_unsupported_sample_rate_raises_controlled_error(tmp_path: Path) -> None:
    wav_path = tmp_path / "rate.wav"
    write_wav(wav_path, 441, sample_rate=44100)

    with pytest.raises(SpeechRegionDetectionError, match="sample rate"):
        SpeechRegionService(detector=FakeFrameDetector()).detect(wav_path)


def test_missing_and_invalid_wav_raise_controlled_errors(tmp_path: Path) -> None:
    missing_path = tmp_path / "missing.wav"
    invalid_path = tmp_path / "invalid.wav"
    invalid_path.write_bytes(b"not a wav")

    with pytest.raises(SpeechRegionDetectionError, match="not found"):
        SpeechRegionService(detector=FakeFrameDetector()).detect(missing_path)
    with pytest.raises(SpeechRegionDetectionError, match="Unable to read WAV"):
        SpeechRegionService(detector=FakeFrameDetector()).detect(invalid_path)
    assert invalid_path.read_bytes() == b"not a wav"


def test_vad_failure_is_controlled_error_not_silence(tmp_path: Path) -> None:
    wav_path = tmp_path / "vad-failure.wav"
    write_vad_frames(wav_path, 1)

    with pytest.raises(SpeechRegionDetectionError, match="Voice activity detector failed"):
        SpeechRegionService(detector=FailingFrameDetector()).detect(wav_path)


@pytest.mark.parametrize(
    "options,error",
    [
        (SpeechRegionOptions(vad_mode=4), "vad_mode"),
        (SpeechRegionOptions(frame_ms=25), "frame_ms"),
        (SpeechRegionOptions(pre_padding_ms=-1), "pre_padding_ms"),
        (SpeechRegionOptions(post_padding_ms=-1), "post_padding_ms"),
        (SpeechRegionOptions(merge_gap_ms=-1), "merge_gap_ms"),
        (SpeechRegionOptions(min_speech_ms=-1), "min_speech_ms"),
    ],
)
def test_option_validation(options: SpeechRegionOptions, error: str) -> None:
    with pytest.raises(SpeechRegionDetectionError, match=error):
        SpeechRegionService(options=options, detector=FakeFrameDetector())
