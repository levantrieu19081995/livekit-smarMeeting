import json
import asyncio
import wave
from pathlib import Path
from typing import Any

from fastapi import HTTPException, status

from app.core.config import Settings, get_settings
from app.core.runtime_limits import get_runtime_limits, stt_execution_slot
from app.core.blocking import run_cancellable_cpu
from app.models.transcription import (
    TranscribeSegmentRequest,
    TranscribeSegmentResponse,
)
from app.services.audio_chunk_service import AudioChunkService
from app.services.openai_stt_provider import OpenAISttProvider
from app.services.stt_provider import SttProvider
from app.utils.path_utils import ensure_relative_to


class TranscriptionService:
    def __init__(
        self,
        settings: Settings | None = None,
        stt_provider: SttProvider | None = None,
        audio_chunk_service: AudioChunkService | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.stt_provider = stt_provider or self._build_stt_provider()
        self.audio_chunk_service = audio_chunk_service or AudioChunkService(
            chunk_seconds=self.settings.STT_CHUNK_SECONDS,
            max_audio_bytes=self.settings.STT_MAX_AUDIO_BYTES,
        )

    async def transcribe_segment(
        self,
        request: TranscribeSegmentRequest,
    ) -> TranscribeSegmentResponse:
        root = self.settings.capture_root_dir.resolve()

        # 1. Resolve manifest
        manifest_path = self._resolve_under_root(
            root,
            request.manifestPath,
        )

        if not manifest_path.exists():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Manifest not found",
            )

        if not manifest_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Manifest path is not a file",
            )

        # 2. Read manifest
        manifest = await asyncio.to_thread(self._read_manifest, manifest_path)

        # 3. Validate meeting
        if manifest.get("meetingId") != request.meetingId:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="meetingId does not match manifest",
            )

        # 4. Find segment
        participant, segment = self._find_segment(
            manifest,
            request.segmentId,
        )

        if segment is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Segment not found",
            )

        # 5. Resolve WAV
        segment_file_path = segment.get("filePath")

        if not segment_file_path:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Segment filePath is missing",
            )

        wav_path = self._resolve_segment_wav(
            root,
            manifest_path.parent,
            segment_file_path,
        )

        if not wav_path.exists():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="WAV file not found",
            )

        if not wav_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="WAV path is not a file",
            )

        if wav_path.suffix.lower() != ".wav":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Segment file is not a WAV",
            )

        # 6. Speech-to-Text
        async with get_runtime_limits(self.settings).preprocessing:
            plans = await run_cancellable_cpu(self._plan_bounded_segment, wav_path)
        texts = []
        for plan in plans:
            chunk = None
            try:
                async with get_runtime_limits(self.settings).preprocessing:
                    creating = asyncio.create_task(asyncio.to_thread(self.audio_chunk_service.materialize_chunk, plan))
                    try:
                        chunk = await asyncio.shield(creating)
                    except asyncio.CancelledError:
                        chunk = await creating
                        raise
                async with stt_execution_slot(self.settings):
                    async with asyncio.timeout(self.settings.STT_TIMEOUT_SECONDS):
                        chunk_text = await self.stt_provider.transcribe(chunk.path)
                if len(chunk_text) > self.settings.STT_MAX_TEXT_CHARS:
                    raise HTTPException(502, detail="STT provider returned excessive text")
                if chunk_text.strip():
                    texts.append(chunk_text.strip())
            finally:
                if chunk is not None:
                    await asyncio.to_thread(self.audio_chunk_service.cleanup_chunk, chunk)
        text = "\n".join(texts).strip()

        # 7. Return transcript + metadata
        return TranscribeSegmentResponse(
            meetingId=request.meetingId,
            sessionId=str(manifest.get("sessionId", "")),
            attendeeId=(
                participant.get("attendeeId")
                if participant
                else None
            ),
            speakerName=(
                participant.get("displayName")
                if participant
                else None
            ),
            segmentId=str(segment.get("segmentId")),
            segmentIndex=int(segment.get("segmentIndex", 0)),
            segmentStartedAt=segment.get("startedAt"),
            durationMs=segment.get("durationMs"),
            filePath=segment_file_path,
            text=text,
        )

    def _resolve_under_root(
        self,
        root: Path,
        path_value: str,
    ) -> Path:
        try:
            path = Path(path_value)

            candidate = (
                path
                if path.is_absolute()
                else root / path
            )

            return ensure_relative_to(
                candidate,
                root,
            )

        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Path is outside CAPTURE_ROOT_DIR",
            ) from exc

    def _resolve_segment_wav(
        self,
        root: Path,
        session_dir: Path,
        file_path: str,
    ) -> Path:
        try:
            candidate = session_dir / file_path

            return ensure_relative_to(
                candidate,
                root,
            )

        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Path is outside CAPTURE_ROOT_DIR",
            ) from exc

    def _read_manifest(
        self,
        manifest_path: Path,
    ) -> dict[str, Any]:
        try:
            if manifest_path.stat().st_size > self.settings.STT_MAX_MANIFEST_BYTES:
                raise HTTPException(413, detail="Capture manifest is too large")
            with manifest_path.open(
                "r",
                encoding="utf-8",
            ) as handle:
                return json.load(handle)

        except json.JSONDecodeError as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Manifest JSON is invalid",
            ) from exc

        except OSError as exc:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Unable to read manifest",
            ) from exc

    def _plan_bounded_segment(self, wav_path: Path):
        with wave.open(str(wav_path), "rb") as handle:
            if handle.getnframes() > handle.getframerate() * self.settings.STT_MAX_SEGMENT_SECONDS:
                raise HTTPException(413, detail="Audio segment is too long")
        plans = self.audio_chunk_service.plan_chunks(wav_path)
        if len(plans) > self.settings.STT_MAX_CHUNKS_PER_JOB:
            raise HTTPException(413, detail="STT job has too many audio chunks")
        return plans

    def _find_segment(
        self,
        manifest: dict[str, Any],
        segment_id: str,
    ) -> tuple[
        dict[str, Any] | None,
        dict[str, Any] | None,
    ]:
        for participant in manifest.get("participants", []):
            for segment in participant.get("segments", []):
                if segment.get("segmentId") == segment_id:
                    return participant, segment

        return None, None

    def _build_stt_provider(self) -> SttProvider:
        if self.settings.STT_PROVIDER == "openai":
            return OpenAISttProvider(self.settings)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unsupported STT provider",
        )

    async def close(self) -> None:
        close = getattr(self.stt_provider, "close", None)
        if close is not None: await close()
