import httpx

from app.core.config import Settings, get_settings
from app.models.transcription import SttProgressCallbackPayload


class SttProgressCallbackClient:
    def __init__(
        self,
        settings: Settings | None = None,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._http_client = http_client or httpx.AsyncClient(
            timeout=self.settings.STT_PROGRESS_CALLBACK_TIMEOUT_SECONDS,
        )
        self._owns_client = http_client is None

    async def send_progress(
        self,
        processing_id: str,
        payload: SttProgressCallbackPayload,
    ) -> None:
        url = (
            f"{self.settings.MEETING_SERVICE_BASE_URL.rstrip('/')}"
            f"/internal/api/minutes/stt/jobs/{processing_id}/progress"
        )
        response = await self._http_client.post(
            url,
            headers={"X-Internal-Api-Key": self.settings.internal_api_key},
            json=payload.model_dump(),
        )
        response.raise_for_status()

    async def close(self) -> None:
        await self._http_client.aclose()
