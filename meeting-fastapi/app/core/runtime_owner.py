import json
import os
import uuid
import struct
from pathlib import Path

from app.core.config import Settings
from app.utils.datetime_utils import iso_utc
from app.utils.path_utils import ensure_relative_to


class RuntimeOwner:
    """Exactly one capture/STT worker owns a storage root, enforced by the OS."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self._handle = None

    def acquire(self) -> None:
        if self._handle is not None:
            return
        self.root.mkdir(parents=True, exist_ok=True)
        handle = (self.root / ".meeting-fastapi-owner.lock").open("a+b")
        try:
            if handle.seek(0, 2) == 0:
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            handle.close()
            raise RuntimeError("Capture/STT storage already has an active worker; run one worker for this storage root") from exc
        self._handle = handle

    def release(self) -> None:
        if self._handle is not None:
            self._handle.close()
            self._handle = None


def validate_runtime_configuration(settings: Settings) -> None:
    if settings.app_environment == "production":
        if not settings.require_internal_api_key:
            raise RuntimeError("Production requires internal API authentication")
        if len(settings.internal_api_key) < 24 or settings.internal_api_key in {"dev-ai-key", "expected-key"}:
            raise RuntimeError("Production requires a non-default internal API key of at least 24 characters")
        if not settings.livekit_api_key or not settings.livekit_api_secret:
            raise RuntimeError("Production requires LiveKit service credentials")
    elif settings.require_internal_api_key and not settings.internal_api_key:
        raise RuntimeError("Internal API key is required when authentication is enabled")
    if settings.STT_MAX_PENDING_JOBS < settings.STT_MAX_ACTIVE_JOBS:
        raise RuntimeError("STT_MAX_PENDING_JOBS must be at least STT_MAX_ACTIVE_JOBS")


def reconcile_interrupted_captures(root: Path) -> int:
    """A manifest cannot truthfully claim RUNNING after its owning process died."""
    reconciled = 0
    for path in root.glob("meeting-*/session-*/manifest.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("status") not in {"STARTING", "RUNNING", "STOPPING"}:
            continue
        data["status"] = "FAILED"
        data["endedAt"] = iso_utc()
        data.setdefault("events", []).append({"type": "CAPTURE_PROCESS_INTERRUPTED", "occurredAt": iso_utc(), "attendeeId": None})
        for participant in data.get("participants", []):
            for segment in participant.get("segments", []):
                if segment.get("status") == "RUNNING":
                    segment["status"] = "FAILED"
                    segment["endedAt"] = data["endedAt"]
                    segment["error"] = "CAPTURE_PROCESS_INTERRUPTED"
                    try:
                        wav = ensure_relative_to(path.parent / segment["filePath"], root)
                        frames, byte_count, rate = _repair_interrupted_pcm_wav(wav)
                        segment["sampleCount"] = frames
                        segment["byteCount"] = byte_count
                        segment["durationMs"] = round(frames * 1000 / rate)
                    except (OSError, KeyError, ValueError, struct.error):
                        segment["error"] = "CAPTURE_PROCESS_INTERRUPTED_AUDIO_UNRECOVERABLE"
        temporary = path.with_suffix(f".{uuid.uuid4().hex}.tmp")
        try:
            with temporary.open("w", encoding="utf-8") as handle:
                json.dump(data, handle, ensure_ascii=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            try: temporary.unlink(missing_ok=True)
            except OSError: pass
        reconciled += 1
    return reconciled


def _repair_interrupted_pcm_wav(path: Path) -> tuple[int, int, int]:
    """Recover only the PCM header format written by SegmentRecorder.

    writeframesraw deliberately avoids a seek per frame. A process crash may
    leave the first batch's RIFF length, although later PCM bytes are intact.
    The interrupted capture stays FAILED; this preserves its usable audio.
    """
    with path.open("r+b") as handle:
        header = handle.read(44)
        if len(header) != 44 or header[:4] != b"RIFF" or header[8:16] != b"WAVEfmt " or header[36:40] != b"data":
            raise ValueError("Unexpected interrupted WAV format")
        fmt_size, encoding, channels, rate, _, alignment, bits = struct.unpack("<IHHIIHH", header[16:36])
        if fmt_size != 16 or encoding != 1 or bits != 16 or channels not in {1, 2} or rate <= 0 or alignment != channels * 2:
            raise ValueError("Unexpected interrupted PCM format")
        size = handle.seek(0, 2)
        data_bytes = ((size - 44) // alignment) * alignment
        if data_bytes < 0 or data_bytes > 0xFFFFFFFF - 36:
            raise ValueError("Interrupted WAV size is invalid")
        handle.seek(4)
        handle.write(struct.pack("<I", 36 + data_bytes))
        handle.seek(40)
        handle.write(struct.pack("<I", data_bytes))
        handle.flush()
        os.fsync(handle.fileno())
        return data_bytes // alignment, data_bytes, rate
