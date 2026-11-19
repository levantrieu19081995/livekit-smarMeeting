import secrets

from fastapi import APIRouter, Depends, Header, HTTPException

from app.core.config import get_settings
from app.models.conclusion import GenerateConclusionRequest, GenerateConclusionResponse
from app.services.conclusion_service import ConclusionService, get_conclusion_service


async def require_conclusion_key(x_internal_api_key: str | None = Header(default=None)) -> None:
    key = get_settings().internal_api_key
    if not key or not x_internal_api_key or not secrets.compare_digest(key, x_internal_api_key):
        raise HTTPException(401, detail="Invalid internal API key")


router = APIRouter(prefix="/api/ai/conclusions", tags=["conclusions"],
                   dependencies=[Depends(require_conclusion_key)])


@router.post("/generate", response_model=GenerateConclusionResponse)
async def generate(request: GenerateConclusionRequest,
                   service: ConclusionService = Depends(get_conclusion_service)) -> GenerateConclusionResponse:
    return await service.generate(request)
