import json
import os
import uuid
import hashlib
import math
import logging
import re
from dataclasses import replace
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from app.models.transcription import TranscribeMeetingResponse


SCHEMA_VERSION = "1.0"
TERMINAL_STATUSES = {"COMPLETED", "PARTIAL", "FAILED"}
NON_TERMINAL_STATUSES = {"QUEUED", "PROCESSING"}
VALID_STATUSES = TERMINAL_STATUSES | NON_TERMINAL_STATUSES
VALID_STAGES = {"QUEUED", "TRANSCRIBING", "COMPLETED", "FAILED"}
EXPECTED_STAGE_BY_STATUS = {
    "QUEUED": "QUEUED",
    "PROCESSING": "TRANSCRIBING",
    "COMPLETED": "COMPLETED",
    "PARTIAL": "COMPLETED",
    "FAILED": "FAILED",
}


class TranscriptionJobStoreError(RuntimeError):
    pass


@dataclass(frozen=True)
class PersistedTranscriptionJob:
    schema_version: str
    request_id: str
    processing_id: str
    meeting_id: int
    manifest_paths: tuple[str, ...]
    status: str
    stage: str
    processed_chunks: int
    total_chunks: int
    progress_percent: float
    error: str | None
    result: TranscribeMeetingResponse | None


class TranscriptionJobStore:
    def __init__(self, root_dir: Path) -> None:
        self.root_dir = root_dir
        self._dirty_jobs: set[str] = set()

    def save(self, job: PersistedTranscriptionJob) -> None:
        self._validate(job)
        self.root_dir.mkdir(parents=True, exist_ok=True)
        path = self._path_for(job.processing_id)
        self._atomic_write(path, self._to_payload(job))
        try:
            self._write_metadata(job)
            self._register_request(job, verify_unique=False)
            self._dirty_jobs.discard(job.processing_id)
        except Exception as exc:
            self._dirty_jobs.add(job.processing_id)
            logging.getLogger(__name__).error("STT derived receipt index requires repair (%s)", type(exc).__name__)

    def _atomic_write(self, path: Path, payload: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(f".{uuid.uuid4().hex}.tmp")
        try:
            with temporary.open("w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, indent=2, allow_nan=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            try: temporary.unlink(missing_ok=True)
            except OSError: pass

    def _write_metadata(self, job: PersistedTranscriptionJob) -> None:
        payload = self._to_payload(job, include_result=False)
        self._atomic_write(self.root_dir / "_status" / self._path_for(job.processing_id).name, payload)

    def _request_path(self, request_id: str) -> Path:
        digest = hashlib.sha256(request_id.encode("utf-8")).hexdigest()
        return self.root_dir / "_requests" / f"{digest}.json"

    def _register_request(self, job: PersistedTranscriptionJob, *, verify_unique: bool) -> None:
        path = self._request_path(job.request_id)
        if path.exists():
            saved = json.loads(path.read_text(encoding="utf-8"))
            if saved.get("requestId") != job.request_id:
                raise TranscriptionJobStoreError("Invalid STT request index")
            if saved.get("processingId") != job.processing_id and verify_unique:
                existing = self.load(saved["processingId"])
                if existing is None: raise TranscriptionJobStoreError("STT request receipt is missing")
                prefix = "Conflicting" if (existing.meeting_id, existing.manifest_paths) != (job.meeting_id, job.manifest_paths) else "Duplicate"
                raise TranscriptionJobStoreError(f"{prefix} STT requestId records: {job.request_id}")
            return
        self._atomic_write(path, {"requestId": job.request_id, "processingId": job.processing_id})

    def rebuild_indexes(self, max_pending: int = 200) -> list[PersistedTranscriptionJob]:
        pending = []
        for job in self.iter_all():
            self._register_request(job, verify_unique=True)
            self._write_metadata(job)
            if job.status in NON_TERMINAL_STATUSES:
                if len(pending) >= max_pending:
                    raise TranscriptionJobStoreError("Persisted STT queue exceeds STT_MAX_PENDING_JOBS")
                pending.append(job)
        return pending

    def load(self, processing_id: str) -> PersistedTranscriptionJob | None:
        path = self._path_for(processing_id)
        return self._load(path) if path.exists() else None

    def metadata(self, processing_id: str) -> PersistedTranscriptionJob | None:
        path = self.root_dir / "_status" / self._path_for(processing_id).name
        original = self._path_for(processing_id)
        if processing_id not in self._dirty_jobs and path.exists() and original.exists() and path.stat().st_mtime_ns >= original.stat().st_mtime_ns:
            return self._load(path, metadata_only=True)
        job = self.load(processing_id)
        if job is not None:
            try: self._write_metadata(job)
            except OSError: self._dirty_jobs.add(processing_id)
            return replace(job, result=None)
        return None

    def find_request(self, request_id: str) -> PersistedTranscriptionJob | None:
        for processing_id in list(self._dirty_jobs):
            job = self.load(processing_id)
            if job is None: raise TranscriptionJobStoreError("STT request receipt is missing")
            try:
                self._write_metadata(job)
                self._register_request(job, verify_unique=True)
                self._dirty_jobs.discard(processing_id)
            except OSError:
                pass
            if job.request_id == request_id:
                return replace(job, result=None)
        path = self._request_path(request_id)
        if not path.exists():
            if self._dirty_jobs: raise TranscriptionJobStoreError("STT receipt index requires repair before accepting new jobs")
            return None
        saved = json.loads(path.read_text(encoding="utf-8"))
        if saved.get("requestId") != request_id or not isinstance(saved.get("processingId"), str):
            raise TranscriptionJobStoreError("Invalid STT request index")
        job = self.metadata(saved["processingId"])
        if job is None: raise TranscriptionJobStoreError("STT request receipt is missing")
        return job

    def iter_all(self):
        if not self.root_dir.exists(): return
        if not self.root_dir.is_dir(): raise TranscriptionJobStoreError("STT job store path is not a directory")
        for path in self.root_dir.glob("*.json"): yield self._load(path)

    def load_all(self) -> list[PersistedTranscriptionJob]:
        if not self.root_dir.exists():
            return []
        if not self.root_dir.is_dir():
            raise TranscriptionJobStoreError(
                f"STT job store path is not a directory: {self.root_dir}"
            )

        jobs: list[PersistedTranscriptionJob] = []
        for path in sorted(self.root_dir.glob("*.json")):
            jobs.append(self._load(path))
        return jobs

    def _load(self, path: Path, metadata_only: bool = False) -> PersistedTranscriptionJob:
        try:
            with path.open("r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except json.JSONDecodeError as exc:
            raise TranscriptionJobStoreError(
                f"Malformed STT job record: {path.name}"
            ) from exc
        except OSError as exc:
            raise TranscriptionJobStoreError(
                f"Cannot read STT job record: {path.name}"
            ) from exc

        if not isinstance(payload, dict):
            raise TranscriptionJobStoreError(
                f"Invalid STT job record shape: {path.name}"
            )

        try:
            result_payload = payload.get("result")
            result = (
                TranscribeMeetingResponse.model_validate(result_payload)
                if result_payload is not None
                else None
            )
            job = PersistedTranscriptionJob(
                schema_version=str(payload.get("schemaVersion", "")),
                request_id=str(payload.get("requestId", "")),
                processing_id=str(payload.get("processingId", "")),
                meeting_id=self._require_int(payload, "meetingId", path),
                manifest_paths=tuple(self._require_manifest_paths(payload, path)),
                status=str(payload.get("status", "")),
                stage=str(payload.get("stage", "")),
                processed_chunks=self._require_int(payload, "processedChunks", path),
                total_chunks=self._require_int(payload, "totalChunks", path),
                progress_percent=self._require_number(payload, "progressPercent", path),
                error=payload.get("error"),
                result=result,
            )
        except ValidationError as exc:
            raise TranscriptionJobStoreError(
                f"Invalid STT job result payload: {path.name}"
            ) from exc

        self._validate(job, path.name, metadata_only=metadata_only)
        if path.name != f"{job.processing_id}.json":
            raise TranscriptionJobStoreError(
                f"STT job record filename does not match processingId: {path.name}"
            )
        return job

    def _path_for(self, processing_id: str) -> Path:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", processing_id):
            raise TranscriptionJobStoreError("Invalid processingId for STT job filename")
        return self.root_dir / f"{processing_id}.json"

    def _to_payload(self, job: PersistedTranscriptionJob, *, include_result: bool = True) -> dict[str, Any]:
        return {
            "schemaVersion": job.schema_version,
            "requestId": job.request_id,
            "processingId": job.processing_id,
            "meetingId": job.meeting_id,
            "manifestPaths": list(job.manifest_paths),
            "status": job.status,
            "stage": job.stage,
            "processedChunks": job.processed_chunks,
            "totalChunks": job.total_chunks,
            "progressPercent": job.progress_percent,
            "error": job.error,
            "result": job.result.model_dump(mode="json") if include_result and job.result is not None else None,
        }

    def _validate(self, job: PersistedTranscriptionJob, source: str | None = None, metadata_only: bool = False) -> None:
        where = f" in {source}" if source else ""
        if job.schema_version != SCHEMA_VERSION:
            raise TranscriptionJobStoreError(f"Unsupported STT job schemaVersion{where}")
        if not job.request_id.strip():
            raise TranscriptionJobStoreError(f"STT job requestId is blank{where}")
        if not job.processing_id.strip():
            raise TranscriptionJobStoreError(f"STT job processingId is blank{where}")
        if job.meeting_id is None:
            raise TranscriptionJobStoreError(f"STT job meetingId is missing{where}")
        if not job.manifest_paths or any(not path.strip() for path in job.manifest_paths):
            raise TranscriptionJobStoreError(f"STT job manifestPaths are invalid{where}")
        if job.status not in VALID_STATUSES:
            raise TranscriptionJobStoreError(f"STT job status is invalid{where}")
        if job.stage not in VALID_STAGES:
            raise TranscriptionJobStoreError(f"STT job stage is invalid{where}")
        expected_stage = EXPECTED_STAGE_BY_STATUS[job.status]
        if job.stage != expected_stage:
            raise TranscriptionJobStoreError(
                f"STT job status/stage pair is invalid{where}"
            )
        if not math.isfinite(job.progress_percent) or job.progress_percent < 0 or job.progress_percent > 100:
            raise TranscriptionJobStoreError(f"STT job progressPercent is invalid{where}")
        if job.processed_chunks < 0 or job.total_chunks < 0:
            raise TranscriptionJobStoreError(f"STT job chunk counts are invalid{where}")
        if job.processed_chunks > job.total_chunks:
            raise TranscriptionJobStoreError(f"STT job processedChunks exceeds totalChunks{where}")

        if job.status in {"COMPLETED", "PARTIAL"} and not metadata_only:
            if job.result is None:
                raise TranscriptionJobStoreError(f"Terminal STT job result is missing{where}")
        if job.result is not None:
            if job.result.meetingId != job.meeting_id:
                raise TranscriptionJobStoreError(f"STT job result meetingId mismatch{where}")
            if job.result.status != job.status:
                raise TranscriptionJobStoreError(f"STT job result status mismatch{where}")
        if job.status in NON_TERMINAL_STATUSES and job.result is not None:
            raise TranscriptionJobStoreError(f"Non-terminal STT job must not have result{where}")

    def _require_int(self, payload: dict[str, Any], field: str, path: Path) -> int:
        value = payload.get(field)
        if not isinstance(value, int):
            raise TranscriptionJobStoreError(f"STT job {field} is invalid: {path.name}")
        return value

    def _require_number(self, payload: dict[str, Any], field: str, path: Path) -> float:
        value = payload.get(field)
        if not isinstance(value, int | float):
            raise TranscriptionJobStoreError(f"STT job {field} is invalid: {path.name}")
        return float(value)

    def _require_manifest_paths(self, payload: dict[str, Any], path: Path) -> list[str]:
        values = payload.get("manifestPaths")
        if not isinstance(values, list) or not values or not all(isinstance(value, str) for value in values):
            raise TranscriptionJobStoreError(f"STT job manifestPaths are invalid: {path.name}")
        return values
