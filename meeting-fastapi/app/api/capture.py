from pathlib import Path
import asyncio

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from fastapi.responses import FileResponse

from app.core.config import get_settings
from app.core.exceptions import InvalidSessionError, SessionNotFoundError, require_internal_api_key
from app.models.capture import CaptureSessionResponse, CaptureStatusResponse, StartCaptureRequest, StopCaptureRequest
from app.services.capture_manager import CaptureManager, get_capture_manager
from app.utils.path_utils import ensure_relative_to

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


def manager() -> CaptureManager:
    return get_capture_manager(get_settings())


@router.post("/api/capture/meetings/{meeting_id}/start", response_model=CaptureSessionResponse, response_model_by_alias=True)
async def start_capture(meeting_id: int, request: StartCaptureRequest, capture_manager: CaptureManager = Depends(manager)) -> CaptureSessionResponse:
    if meeting_id != request.meeting_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="meetingId path/body mismatch")
    try:
        session = await capture_manager.start(request)
    except InvalidSessionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Capture could not start; check status before retrying") from exc
    return CaptureSessionResponse(
        sessionId=session.session_id,
        status=session.status,
        message="Audio capture started",
        manifestPath=session.manifest_relative_path,
    )


@router.post("/api/capture/meetings/{meeting_id}/stop", response_model=CaptureSessionResponse, response_model_by_alias=True)
async def stop_capture(meeting_id: int, request: StopCaptureRequest, capture_manager: CaptureManager = Depends(manager)) -> CaptureSessionResponse:
    if meeting_id != request.meeting_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="meetingId path/body mismatch")
    try:
        session = await capture_manager.stop(meeting_id, request.external_session_id)
    except InvalidSessionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except SessionNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Capture could not stop; check status and retry") from exc
    return CaptureSessionResponse(
        sessionId=session.session_id,
        status=session.status,
        message="Audio capture stopped and manifest finalized",
        manifestPath=session.manifest_relative_path,
        completedAt=session.completed_at_iso,
    )


@router.get("/api/capture/meetings/{meeting_id}/status", response_model=CaptureStatusResponse)
async def capture_status(
    meeting_id: int,
    capture_session_id: int | None = Query(default=None, alias="captureSessionId", ge=1),
    external_session_id: str | None = Query(default=None, alias="externalSessionId", min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$"),
    capture_manager: CaptureManager = Depends(manager),
) -> CaptureStatusResponse:
    if capture_session_id is not None or external_session_id:
        return CaptureStatusResponse(
            **await asyncio.to_thread(capture_manager.correlated_status,
                meeting_id,
                capture_session_id,
                external_session_id,
            )
        )
    session = capture_manager.get(meeting_id)
    capture_status_value, message = capture_manager.status(meeting_id)
    return CaptureStatusResponse(
        captureSessionId=session.capture_session_id if session else None,
        runtimePresent=session is not None,
        sessionId=session.session_id if session else None,
        status=capture_status_value,
        message=message,
        manifestPath=session.manifest_relative_path if session else None,
        completedAt=session.completed_at_iso if session else None,
    )


@router.get("/api/capture/meetings/{meeting_id}/manifest")
async def get_manifest(meeting_id: int, capture_manager: CaptureManager = Depends(manager)) -> dict:
    try:
        return await asyncio.to_thread(capture_manager.latest_manifest, meeting_id)
    except SessionNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/api/capture/meetings/{meeting_id}/segments")
async def list_segments(meeting_id: int, capture_manager: CaptureManager = Depends(manager)) -> dict:
    manifest = await get_manifest(meeting_id, capture_manager)
    segments = []
    for participant in manifest.get("participants", []):
        for segment in participant.get("segments", []):
            segments.append({"attendeeId": participant.get("attendeeId"), **segment})
    return {"meetingId": meeting_id, "segments": segments}


@router.get("/api/capture/meetings/{meeting_id}/segments/{segment_id}/download")
async def download_segment(meeting_id: int, segment_id: str, capture_manager: CaptureManager = Depends(manager)) -> Response:
    settings = get_settings()
    manifest = await get_manifest(meeting_id, capture_manager)
    root = settings.capture_root_dir.resolve()
    session_dir = Path(capture_manager.latest_manifest_path(meeting_id)).parent
    for participant in manifest.get("participants", []):
        for segment in participant.get("segments", []):
            if segment.get("segmentId") == segment_id:
                candidate = ensure_relative_to(session_dir / segment["filePath"], root)
                if not candidate.exists():
                    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Segment file not found")
                return FileResponse(candidate, media_type="audio/wav", filename=candidate.name)
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Segment not found")
