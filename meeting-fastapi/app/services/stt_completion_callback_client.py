from collections.abc import Awaitable, Callable

import httpx

from app.core.config import Settings, get_settings
from app.models.transcription import SttCompletionCallbackPayload

_TRANSIENT_HTTP_STATUSES = {404, 408, 425, 429}


class SttCompletionCallbackClient:
    def __init__(
        self,
        settings: Settings | None = None,
        http_client: httpx.AsyncClient | None = None,
        sleep: Callable[[float], Awaitable[None]] | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._http_client = http_client or httpx.AsyncClient(
            timeout=self.settings.STT_COMPLETION_CALLBACK_TIMEOUT_SECONDS,
        )
        self._sleep = sleep

    async def send_completion(
        self,
        processing_id: str,
        payload: SttCompletionCallbackPayload,
    ) -> None:
        last_exc: Exception | None = None
        max_attempts = self.settings.STT_COMPLETION_CALLBACK_MAX_ATTEMPTS
        for attempt in range(1, max_attempts + 1):
            try:
                response = await self._http_client.post(
                    self._url(processing_id),
                    headers={"X-Internal-Api-Key": self.settings.internal_api_key},
                    json=payload.model_dump(mode="json"),
                )
                response.raise_for_status()
                return
            except httpx.HTTPStatusError as exc:
                last_exc = exc
                if not self._should_retry_http_status(exc.response.status_code) or attempt == max_attempts:
                    raise
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                last_exc = exc
                if attempt == max_attempts:
                    raise

            await self._sleep_before_retry(attempt)

        if last_exc is not None:
            raise last_exc

    async def close(self) -> None:
        await self._http_client.aclose()

    def _url(self, processing_id: str) -> str:
        return (
            f"{self.settings.MEETING_SERVICE_BASE_URL.rstrip('/')}"
            f"/internal/api/minutes/stt/jobs/{processing_id}/completion"
        )

    def _should_retry_http_status(self, status_code: int) -> bool:
        return status_code in _TRANSIENT_HTTP_STATUSES or status_code >= 500

    async def _sleep_before_retry(self, failed_attempt: int) -> None:
        delay = self.settings.STT_COMPLETION_CALLBACK_RETRY_BASE_SECONDS * (2 ** (failed_attempt - 1))
        if self._sleep is not None:
            await self._sleep(delay)
            return

        import asyncio

        await asyncio.sleep(delay)
