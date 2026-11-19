from fastapi import APIRouter

from app.models.capture import HealthResponse

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="UP", message="Meeting capture service is running")

