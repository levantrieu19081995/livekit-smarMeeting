from fastapi import APIRouter, Depends, Path

from app.models.transcription import (
    StartTranscriptionJobResponse,
    TranscribeManifestRequest,
    TranscribeManifestResponse,
    TranscribeMeetingRequest,
    TranscribeSegmentRequest,
    TranscribeSegmentResponse,
    TranscriptionJobResultResponse,
    TranscriptionJobStatusResponse,
)
from app.core.exceptions import require_internal_api_key
from app.services.manifest_transcription_service import ManifestTranscriptionService
from app.services.transcription_job_service import TranscriptionJobService, get_transcription_job_service
from app.services.transcription_service import TranscriptionService

router = APIRouter(
    prefix="/api/postprocessing/stt",
    tags=["postprocessing-stt"],
    dependencies=[Depends(require_internal_api_key)],
)


async def transcription_service():
    service = TranscriptionService()
    try:
        yield service
    finally:
        await service.close()


async def manifest_transcription_service():
    service = ManifestTranscriptionService()
    try:
        yield service
    finally:
        await service.close()


def transcription_job_service() -> TranscriptionJobService:
    return get_transcription_job_service()


@router.post("/segments/transcribe", response_model=TranscribeSegmentResponse)
async def transcribe_segment(
    request: TranscribeSegmentRequest,
    service: TranscriptionService = Depends(transcription_service),
) -> TranscribeSegmentResponse:
    return await service.transcribe_segment(request)


@router.post("/manifests/transcribe", response_model=TranscribeManifestResponse)
async def transcribe_manifest(
    request: TranscribeManifestRequest,
    service: ManifestTranscriptionService = Depends(manifest_transcription_service),
) -> TranscribeManifestResponse:
    return await service.transcribe_manifest(request)


@router.post("/jobs", response_model=StartTranscriptionJobResponse, status_code=202)
async def start_transcription_job(
    request: TranscribeMeetingRequest,
    service: TranscriptionJobService = Depends(transcription_job_service),
) -> StartTranscriptionJobResponse:
    return await service.start_job(request)


@router.get("/jobs/{processing_id}", response_model=TranscriptionJobStatusResponse)
async def get_transcription_job_status(
    processing_id: str = Path(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$"),
    service: TranscriptionJobService = Depends(transcription_job_service),
) -> TranscriptionJobStatusResponse:
    return await service.get_status(processing_id)


@router.get("/jobs/{processing_id}/result", response_model=TranscriptionJobResultResponse)
async def get_transcription_job_result(
    processing_id: str = Path(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$"),
    service: TranscriptionJobService = Depends(transcription_job_service),
) -> TranscriptionJobResultResponse:
    return await service.get_result(processing_id)
