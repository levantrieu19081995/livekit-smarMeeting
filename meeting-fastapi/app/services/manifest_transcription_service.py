import asyncio
import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Awaitable, Callable

from fastapi import HTTPException, status

from app.core.config import Settings, get_settings
from app.core.runtime_limits import get_runtime_limits, stt_execution_slot
from app.core.blocking import check_cancelled, run_cancellable_cpu
from app.services.stt_chunk_receipts import SttChunkReceipts
import wave
from app.models.transcription import (
    FailedTranscriptChunk,
    TranscribeManifestRequest,
    TranscribeManifestResponse,
    TranscribeMeetingRequest,
    TranscribeMeetingResponse,
    TranscriptChunkResult,
)
from app.services.audio_chunk_service import AudioChunk, AudioChunkPlan, AudioChunkService
from app.services.openai_stt_provider import OpenAISttProvider
from app.services.speech_region_service import SpeechRegionService
from app.services.stt_provider import SttProvider
from app.utils.path_utils import ensure_relative_to

logger = logging.getLogger(__name__)

ProgressCallback = Callable[[int, int, float], Awaitable[None]]


@dataclass
class _WorkItem:
    session_id: str
    participant: dict[str, Any]
    segment: dict[str, Any]
    wav_path: Path
    plan: AudioChunkPlan


class ManifestTranscriptionService:
    def __init__(
        self,
        settings: Settings | None = None,
        stt_provider: SttProvider | None = None,
        audio_chunk_service: AudioChunkService | None = None,
        speech_region_service: SpeechRegionService | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.stt_provider = stt_provider or self._build_stt_provider()
        self.audio_chunk_service = audio_chunk_service or AudioChunkService(
            chunk_seconds=self.settings.STT_CHUNK_SECONDS,
            max_audio_bytes=self.settings.STT_MAX_AUDIO_BYTES,
        )
        self.speech_region_service = speech_region_service
        if self.settings.STT_SPEECH_REGION_ENABLED and self.speech_region_service is None:
            try:
                self.speech_region_service = SpeechRegionService()
            except Exception as exc:
                logger.warning(
                    "Speech region detection is unavailable; using legacy STT chunk planning: %s",
                    exc,
                    exc_info=True,
                )
                self.speech_region_service = None

    async def transcribe_manifest(
        self,
        request: TranscribeManifestRequest,
        progress_callback: ProgressCallback | None = None,
    ) -> TranscribeManifestResponse:
        root = self.settings.capture_root_dir.resolve()
        manifest_path = self._resolve_under_root(root, request.manifestPath)
        if not manifest_path.exists():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Manifest not found")
        if not manifest_path.is_file():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Manifest path is not a file")

        manifest = await asyncio.to_thread(self._read_manifest, manifest_path)
        if manifest.get("meetingId") != request.meetingId:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="meetingId does not match manifest")

        session_id = str(manifest.get("sessionId", ""))
        segment_keys = self._segment_keys(manifest, session_id)
        async with get_runtime_limits(self.settings).preprocessing:
            work_items, planning_failures = await run_cancellable_cpu(self._build_work_items,
            root,
            manifest_path.parent,
            manifest,
            session_id=session_id,
            isolate_wav_errors=False,
        )
        transcripts, failures, processed_chunks, total_chunks = await self._run_work_items(
            meeting_id=request.meetingId,
            work_items=work_items,
            planning_failures=planning_failures,
            progress_callback=progress_callback,
        )

        transcripts.sort(key=self._transcript_sort_key)
        succeeded_chunks = len(transcripts)
        failed_chunks = len(failures)
        return TranscribeManifestResponse(
            meetingId=request.meetingId,
            sessionId=session_id,
            status=self._response_status(succeeded_chunks, failed_chunks),
            totalSegments=len(segment_keys),
            totalChunks=total_chunks,
            processedChunks=processed_chunks,
            succeededChunks=succeeded_chunks,
            failedChunks=failed_chunks,
            transcripts=transcripts,
            failures=failures,
        )

    async def transcribe_meeting(
        self,
        request: TranscribeMeetingRequest,
        progress_callback: ProgressCallback | None = None,
    ) -> TranscribeMeetingResponse:
        root = self.settings.capture_root_dir.resolve()
        session_ids: list[str] = []
        segment_keys: set[tuple[str, str]] = set()
        work_items: list[_WorkItem] = []
        planning_failures: list[FailedTranscriptChunk] = []

        for manifest_path_value in request.manifestPaths:
            manifest_path = self._resolve_under_root(root, manifest_path_value)
            if not manifest_path.exists():
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Manifest not found")
            if not manifest_path.is_file():
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Manifest path is not a file")

            manifest = await asyncio.to_thread(self._read_manifest, manifest_path)
            if manifest.get("meetingId") != request.meetingId:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="meetingId does not match manifest")

            session_id = str(manifest.get("sessionId", ""))
            session_ids.append(session_id)
            manifest_keys = self._segment_keys(manifest, session_id)
            if segment_keys.intersection(manifest_keys):
                raise HTTPException(400, detail="Duplicate capture segment identity across manifests")
            segment_keys.update(manifest_keys)
            async with get_runtime_limits(self.settings).preprocessing:
                manifest_items, manifest_failures = await run_cancellable_cpu(self._build_work_items,
                root,
                manifest_path.parent,
                manifest,
                session_id=session_id,
                isolate_wav_errors=True,
            )
            work_items.extend(manifest_items)
            planning_failures.extend(manifest_failures)
            if len(work_items) + len(planning_failures) > self.settings.STT_MAX_CHUNKS_PER_JOB:
                raise HTTPException(413, detail="STT job has too many audio chunks")

        transcripts, failures, processed_chunks, total_chunks = await self._run_work_items(
            meeting_id=request.meetingId,
            work_items=work_items,
            planning_failures=planning_failures,
            progress_callback=progress_callback,
            request_id=request.requestId,
        )

        transcripts.sort(key=self._transcript_sort_key)
        succeeded_chunks = len(transcripts)
        failed_chunks = len(failures)
        return TranscribeMeetingResponse(
            meetingId=request.meetingId,
            sessionIds=session_ids,
            status=self._response_status(succeeded_chunks, failed_chunks),
            totalManifests=len(request.manifestPaths),
            totalSegments=len(segment_keys | {(failure.sessionId, failure.segmentId) for failure in failures}),
            totalChunks=total_chunks,
            processedChunks=processed_chunks,
            succeededChunks=succeeded_chunks,
            failedChunks=failed_chunks,
            transcripts=transcripts,
            failures=failures,
        )

    async def _run_work_items(
        self,
        *,
        meeting_id: int,
        work_items: list[_WorkItem],
        planning_failures: list[FailedTranscriptChunk],
        progress_callback: ProgressCallback | None,
        request_id: str | None = None,
    ) -> tuple[list[TranscriptChunkResult], list[FailedTranscriptChunk], int, int]:
        total_chunks = len(work_items) + len(planning_failures)
        if total_chunks > self.settings.STT_MAX_CHUNKS_PER_JOB:
            raise HTTPException(413, detail="STT job has too many audio chunks")
        queue: asyncio.Queue[_WorkItem | None] = asyncio.Queue(maxsize=self.settings.STT_CONCURRENCY * 2)
        transcripts: list[TranscriptChunkResult] = []
        failures: list[FailedTranscriptChunk] = list(planning_failures)
        processed_chunks = len(planning_failures)
        lock = asyncio.Lock()

        async def mark_progress() -> None:
            nonlocal processed_chunks
            async with lock:
                processed_chunks += 1
                percent = 100 if total_chunks == 0 else processed_chunks * 100 / total_chunks
                logger.info(
                    "STT progress meeting=%s processed=%s total=%s percent=%s",
                    meeting_id,
                    processed_chunks,
                    total_chunks,
                    percent,
                )
                if progress_callback is not None:
                    await progress_callback(processed_chunks, total_chunks, percent)

        if progress_callback is not None:
            percent = 100 if total_chunks == 0 else processed_chunks * 100 / total_chunks
            await progress_callback(processed_chunks, total_chunks, percent)

        async def worker() -> None:
            while True:
                item = await queue.get()
                try:
                    if item is None:
                        return
                    result, failure = await self._process_item(item, request_id=request_id)
                    async with lock:
                        if result is not None:
                            transcripts.append(result)
                        if failure is not None:
                            failures.append(failure)
                    await mark_progress()
                finally:
                    queue.task_done()

        async def produce():
            for item in work_items:
                await queue.put(item)
            for _ in range(self.settings.STT_CONCURRENCY):
                await queue.put(None)

        # A failed worker must cancel a blocked producer immediately. Waiting
        # queue.join() after all consumers failed would otherwise deadlock.
        async with asyncio.TaskGroup() as group:
            group.create_task(produce())
            for _ in range(self.settings.STT_CONCURRENCY):
                group.create_task(worker())

        return transcripts, failures, processed_chunks, total_chunks

    async def _process_item(
        self,
        item: _WorkItem,
        request_id: str | None = None,
    ) -> tuple[TranscriptChunkResult | None, FailedTranscriptChunk | None]:
        chunk: AudioChunk | None = None
        attempts = 0
        try:
            async with get_runtime_limits(self.settings).preprocessing:
                materialization = asyncio.create_task(asyncio.to_thread(self.audio_chunk_service.materialize_chunk, item.plan))
                try:
                    chunk = await asyncio.shield(materialization)
                except asyncio.CancelledError:
                    chunk = await materialization
                    raise
            receipt = None
            cached_text = None
            receipt_persisted = False
            receipts = SttChunkReceipts(self.settings)
            if request_id is not None:
                receipt = await asyncio.to_thread(receipts.path_and_fingerprint, request_id, item, chunk)
                cached_text = await asyncio.to_thread(receipts.read, *receipt)
                receipt_persisted = cached_text is not None
            for attempt in range(1, self.settings.STT_MAX_RETRIES + 2):
                attempts = attempt
                try:
                    if cached_text is None:
                        async with stt_execution_slot(self.settings):
                            if receipt is not None:
                                reserving = asyncio.create_task(asyncio.to_thread(receipts.reserve, *receipt))
                                try:
                                    await asyncio.shield(reserving)
                                except asyncio.CancelledError:
                                    await reserving
                                    raise
                            async with asyncio.timeout(self.settings.STT_TIMEOUT_SECONDS):
                                text = (await self.stt_provider.transcribe(chunk.path)).strip()
                        cached_text = text
                    else:
                        text = cached_text
                    if len(text) > self.settings.STT_MAX_TEXT_CHARS: raise HTTPException(502, detail="STT provider returned excessive text")
                    if receipt is not None and not receipt_persisted:
                        saving = asyncio.create_task(asyncio.to_thread(receipts.write, *receipt, text))
                        try:
                            await asyncio.shield(saving)
                        except asyncio.CancelledError:
                            await saving
                            raise
                        receipt_persisted = True
                    segment_started_at = self._parse_datetime(item.segment.get("startedAt"))
                    return (
                        TranscriptChunkResult(
                            sessionId=item.session_id,
                            attendeeId=item.participant.get("attendeeId"),
                            speakerName=item.participant.get("displayName"),
                            segmentId=str(item.segment.get("segmentId")),
                            segmentIndex=int(item.segment.get("segmentIndex", 0)),
                            chunkIndex=chunk.chunkIndex,
                            chunkStartMs=chunk.startMs,
                            chunkDurationMs=chunk.durationMs,
                            segmentStartedAt=segment_started_at,
                            absoluteStartTime=self._absolute_start_time(segment_started_at, chunk.startMs),
                            filePath=str(item.segment.get("filePath", "")),
                            text=text,
                        ),
                        None,
                    )
                except Exception as exc:
                    logger.error("STT chunk operation failed (%s)", type(exc).__name__)
                    known_retry = getattr(exc, "retryable", False)
                    if receipt is not None and cached_text is None and known_retry:
                        await asyncio.to_thread(receipts.allow_retry, *receipt)
                    if attempt > self.settings.STT_MAX_RETRIES or isinstance(exc, TimeoutError) or not getattr(exc, "retryable", receipt is None or cached_text is not None):
                        return None, self._failure(item, chunk.chunkIndex, attempts, exc)
                    await asyncio.sleep(self.settings.STT_RETRY_BASE_SECONDS * (2 ** (attempt - 1)))
        except Exception as exc:
            logger.error("STT chunk preparation failed (%s)", type(exc).__name__)
            chunk_index = chunk.chunkIndex if chunk else item.plan.chunkIndex
            return None, self._failure(item, chunk_index, attempts, exc)
        finally:
            if chunk is not None:
                await asyncio.to_thread(self.audio_chunk_service.cleanup_chunk, chunk)

    def _build_work_items(
        self,
        root: Path,
        session_dir: Path,
        manifest: dict[str, Any],
        *,
        session_id: str,
        isolate_wav_errors: bool,
    ) -> tuple[list[_WorkItem], list[FailedTranscriptChunk]]:
        items: list[_WorkItem] = []
        failures: list[FailedTranscriptChunk] = []

        for participant in manifest.get("participants", []):
            for segment in participant.get("segments", []):
                check_cancelled()
                file_path = segment.get("filePath")
                if not file_path:
                    continue
                try:
                    wav_path = self._resolve_segment_wav(root, session_dir, file_path)
                    if not wav_path.exists():
                        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="WAV file not found")
                    if not wav_path.is_file():
                        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="WAV path is not a file")
                    if wav_path.suffix.lower() != ".wav":
                        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Segment file is not a WAV")
                    plans = self._plan_wav_for_stt(wav_path)
                except Exception as exc:
                    check_cancelled()
                    if not isolate_wav_errors:
                        raise
                    failures.append(
                        self._planning_failure(
                            session_id=session_id,
                            segment=segment,
                            file_path=file_path,
                            exc=exc,
                        )
                    )
                    continue

                for plan in plans:
                    if len(items) + len(failures) >= self.settings.STT_MAX_CHUNKS_PER_JOB:
                        raise HTTPException(413, detail="STT job has too many audio chunks")
                    items.append(
                        _WorkItem(
                            session_id=session_id,
                            participant=participant,
                            segment=segment,
                            wav_path=wav_path,
                            plan=plan,
                        )
                    )

        return items, failures

    def _plan_wav_for_stt(self, wav_path: Path) -> list[AudioChunkPlan]:
        check_cancelled()
        with wave.open(str(wav_path), "rb") as source:
            if source.getframerate() <= 0 or source.getnframes() / source.getframerate() > self.settings.STT_MAX_SEGMENT_SECONDS:
                raise HTTPException(413, detail="Capture audio segment is too long")
        if not self.settings.STT_SPEECH_REGION_ENABLED or self.speech_region_service is None:
            return self.audio_chunk_service.plan_chunks(wav_path)

        try:
            regions = self.speech_region_service.detect(wav_path)
            plans = self.audio_chunk_service.plan_speech_regions(wav_path, regions)
            if not regions:
                logger.info("Speech region detection found no speech; skipping STT chunks for wav=%s", wav_path)
            return plans
        except Exception as exc:
            check_cancelled()
            logger.warning(
                "Speech region planning failed for wav=%s; falling back to legacy STT chunk planning: %s",
                wav_path,
                exc,
                exc_info=True,
            )
            return self.audio_chunk_service.plan_chunks(wav_path)

    def _segment_keys(self, manifest: dict[str, Any], session_id: str) -> set[tuple[str, str]]:
        keys: set[tuple[str, str]] = set()
        for participant in manifest.get("participants", []):
            for segment in participant.get("segments", []):
                if segment.get("filePath"):
                    keys.add((session_id, str(segment.get("segmentId"))))
        return keys

    def _failure(
        self,
        item: _WorkItem,
        chunk_index: int,
        attempts: int,
        exc: Exception,
    ) -> FailedTranscriptChunk:
        detail = exc.detail if isinstance(exc, HTTPException) else "Unable to transcribe audio chunk"
        return FailedTranscriptChunk(
            sessionId=item.session_id,
            segmentId=str(item.segment.get("segmentId")),
            chunkIndex=chunk_index,
            filePath=str(item.segment.get("filePath", "")),
            attempts=attempts,
            error=str(detail),
        )

    def _planning_failure(
        self,
        *,
        session_id: str,
        segment: dict[str, Any],
        file_path: str,
        exc: Exception,
    ) -> FailedTranscriptChunk:
        detail = exc.detail if isinstance(exc, HTTPException) else "Unable to prepare audio chunk"
        return FailedTranscriptChunk(
            sessionId=session_id,
            segmentId=str(segment.get("segmentId")),
            chunkIndex=None,
            filePath=file_path,
            attempts=0,
            error=str(detail),
        )

    def _resolve_under_root(self, root: Path, path_value: str) -> Path:
        try:
            path = Path(path_value)
            candidate = path if path.is_absolute() else root / path
            return ensure_relative_to(candidate, root)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path is outside CAPTURE_ROOT_DIR") from exc

    def _resolve_segment_wav(self, root: Path, session_dir: Path, file_path: str) -> Path:
        try:
            return ensure_relative_to(session_dir / file_path, root)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path is outside CAPTURE_ROOT_DIR") from exc

    def _read_manifest(self, manifest_path: Path) -> dict[str, Any]:
        try:
            if manifest_path.stat().st_size > self.settings.STT_MAX_MANIFEST_BYTES:
                raise HTTPException(413, detail="Capture manifest is too large")
            with manifest_path.open("r", encoding="utf-8") as handle:
                manifest = json.load(handle)
            if not isinstance(manifest, dict) or not isinstance(manifest.get("participants", []), list) or len(manifest.get("participants", [])) > 1000:
                raise HTTPException(400, detail="Capture manifest structure is invalid")
            total = 0
            seen = set()
            for participant in manifest.get("participants", []):
                if not isinstance(participant, dict) or not isinstance(participant.get("segments", []), list):
                    raise HTTPException(400, detail="Capture manifest structure is invalid")
                total += len(participant.get("segments", []))
                if total > self.settings.STT_MAX_CHUNKS_PER_JOB:
                    raise HTTPException(413, detail="Capture manifest has too many segments")
                if any(not isinstance(segment, dict) for segment in participant.get("segments", [])):
                    raise HTTPException(400, detail="Capture manifest structure is invalid")
                for segment in participant.get("segments", []):
                    segment_id = segment.get("segmentId")
                    if not isinstance(segment_id, str) or not segment_id or segment_id in seen:
                        raise HTTPException(400, detail="Capture manifest segment identities are invalid")
                    seen.add(segment_id)
            return manifest
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Manifest JSON is invalid") from exc
        except OSError as exc:
            raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Unable to read manifest") from exc

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

    def _parse_datetime(self, value: Any) -> datetime | None:
        if not value:
            return None
        if isinstance(value, datetime):
            return value
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except ValueError:
            return None

    def _absolute_start_time(self, segment_started_at: datetime | None, chunk_start_ms: int) -> datetime | None:
        if segment_started_at is None:
            return None
        return segment_started_at + timedelta(milliseconds=chunk_start_ms)

    def _transcript_sort_key(self, transcript: TranscriptChunkResult) -> tuple[int, datetime, str, int, int]:
        if transcript.absoluteStartTime is None:
            return (1, datetime.max, transcript.sessionId, transcript.segmentIndex, transcript.chunkIndex)
        return (0, transcript.absoluteStartTime, transcript.sessionId, transcript.segmentIndex, transcript.chunkIndex)

    def _response_status(self, succeeded_chunks: int, failed_chunks: int) -> str:
        if failed_chunks == 0:
            return "COMPLETED"
        if succeeded_chunks > 0:
            return "PARTIAL"
        return "FAILED"
