import httpx
import pytest

from app.core.config import Settings
from app.models.transcription import SttProgressCallbackPayload
from app.services.stt_progress_callback_client import SttProgressCallbackClient


def settings() -> Settings:
    return Settings(
        MEETING_SERVICE_BASE_URL="http://meeting-service.test/",
        internal_api_key="test-internal-key",
        STT_PROGRESS_CALLBACK_TIMEOUT_SECONDS=1.0,
    )


@pytest.mark.asyncio
async def test_send_progress_success_contract() -> None:
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured["method"] = request.method
        captured["path"] = request.url.path
        captured["api_key"] = request.headers.get("X-Internal-Api-Key")
        captured["body"] = request.read()
        return httpx.Response(204)

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = SttProgressCallbackClient(settings(), http_client=http_client)

    await client.send_progress(
        "python-123",
        SttProgressCallbackPayload(
            meetingId=12,
            processedChunks=1,
            totalChunks=3,
            progressPercent=33.33,
        ),
    )

    assert captured["method"] == "POST"
    assert captured["path"] == "/internal/api/minutes/stt/jobs/python-123/progress"
    assert captured["api_key"] == "test-internal-key"
    assert captured["body"] == b'{"meetingId":12,"processedChunks":1,"totalChunks":3,"progressPercent":33.33}'
    await client.close()


@pytest.mark.asyncio
async def test_send_progress_401_raises() -> None:
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(401)))
    client = SttProgressCallbackClient(settings(), http_client=http_client)

    with pytest.raises(httpx.HTTPStatusError):
        await client.send_progress("python-123", SttProgressCallbackPayload(meetingId=12, processedChunks=1, totalChunks=3, progressPercent=33.33))

    await client.close()


@pytest.mark.asyncio
async def test_send_progress_500_raises() -> None:
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(500)))
    client = SttProgressCallbackClient(settings(), http_client=http_client)

    with pytest.raises(httpx.HTTPStatusError):
        await client.send_progress("python-123", SttProgressCallbackPayload(meetingId=12, processedChunks=1, totalChunks=3, progressPercent=33.33))

    await client.close()


@pytest.mark.asyncio
async def test_send_progress_network_error_propagates() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = SttProgressCallbackClient(settings(), http_client=http_client)

    with pytest.raises(httpx.ConnectError):
        await client.send_progress("python-123", SttProgressCallbackPayload(meetingId=12, processedChunks=1, totalChunks=3, progressPercent=33.33))

    await client.close()


@pytest.mark.asyncio
async def test_close_closes_async_client() -> None:
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(204)))
    client = SttProgressCallbackClient(settings(), http_client=http_client)

    await client.close()

    assert http_client.is_closed
