import hashlib
import json
import os
import uuid
from pathlib import Path

from fastapi import HTTPException

from app.core.config import Settings
from app.utils.path_utils import to_io_path


class SttChunkReceipts:
    """Completed paid chunk output survives process restart before job completion."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def path_and_fingerprint(self, request_id: str, item, chunk) -> tuple[Path, str]:
        audio = hashlib.sha256()
        with chunk.path.open("rb") as handle:
            while data := handle.read(65536): audio.update(data)
        identity = json.dumps({
            "sessionId": item.session_id, "segmentId": item.segment.get("segmentId"),
            "startFrame": item.plan.startFrame, "frameCount": item.plan.frameCount,
            "audio": audio.hexdigest(), "provider": self.settings.STT_PROVIDER,
            "model": self.settings.STT_MODEL, "prompt": self.settings.STT_TRANSCRIPTION_PROMPT,
        }, sort_keys=True, ensure_ascii=False)
        fingerprint = hashlib.sha256(identity.encode()).hexdigest()
        request_key = hashlib.sha256(request_id.encode()).hexdigest()
        return to_io_path(self.settings.capture_root_dir / "_stt_chunk_receipts" / request_key / f"{fingerprint}.json"), fingerprint

    def read(self, path: Path, fingerprint: str) -> str | None:
        if not path.exists(): return None
        try:
            if path.stat().st_size > self.settings.STT_MAX_TEXT_CHARS * 4 + 1024:
                raise ValueError("Oversized receipt")
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("fingerprint") != fingerprint:
                raise ValueError("Invalid receipt")
            if data.get("status") == "PROCESSING":
                raise HTTPException(502, detail="STT provider outcome is unknown; manual reconciliation is required")
            if data.get("status") == "RETRYABLE":
                return None
            if data.get("status") not in {None, "COMPLETED"}:
                raise ValueError("Invalid receipt")
            text = data["text"]
            if data["fingerprint"] != fingerprint or not isinstance(text, str) or len(text) > self.settings.STT_MAX_TEXT_CHARS:
                raise ValueError("Invalid receipt")
            return text
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise HTTPException(502, detail="STT chunk receipt is invalid") from exc

    def write(self, path: Path, fingerprint: str, text: str) -> None:
        self._write_payload(path, {"fingerprint": fingerprint, "status": "COMPLETED", "text": text})

    def reserve(self, path: Path, fingerprint: str) -> None:
        self._write_payload(path, {"fingerprint": fingerprint, "status": "PROCESSING"})

    def allow_retry(self, path: Path, fingerprint: str) -> None:
        self._write_payload(path, {"fingerprint": fingerprint, "status": "RETRYABLE"})

    def _write_payload(self, path: Path, payload: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(f".{uuid.uuid4().hex}.tmp")
        try:
            with temporary.open("w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            try: temporary.unlink(missing_ok=True)
            except OSError: pass
