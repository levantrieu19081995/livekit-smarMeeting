import httpx
import pytest

from app.core.config import Settings
from app.models.transcription import SttCompletionCallbackPayload, SttTerminalStatus
from app.services.stt_completion_callback_client import SttCompletionCallbackClient


def settings(max_attempts: int = 5) -> Settings:
    return Settings(
        MEETING_SERVICE_BASE_URL="http://meeting-service.test/",
        internal_api_key="test-internal-key",
        STT_COMPLETION_CALLBACK_TIMEOUT_SECONDS=1.0,
        STT_COMPLETION_CALLBACK_MAX_ATTEMPTS=max_attempts,
        STT_COMPLETION_CALLBACK_RETRY_BASE_SECONDS=0.0,
    )


def payload() -> SttCompletionCallbackPayload:
    return SttCompletionCallbackPayload(
        meetingId=12,
        status=SttTerminalStatus.COMPLETED,
        totalManifests=2,
        totalSegments=3,
        totalChunks=3,
        processedChunks=3,
        succeededChunks=3,
        failedChunks=0,
        errorMessage=None,
    )


async def no_sleep(delay: float) -> None:
    return None


@pytest.mark.asyncio
async def test_send_completion_success_contract() -> None:
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured["method"] = request.method
        captured["path"] = request.url.path
        captured["api_key"] = request.headers.get("X-Internal-Api-Key")
        captured["body"] = request.read()
        return httpx.Response(204)

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = SttCompletionCallbackClient(settings(), http_client=http_client, sleep=no_sleep)

    await client.send_completion("python-123", payload())

    assert captured["method"] == "POST"
    assert captured["path"] == "/internal/api/minutes/stt/jobs/python-123/completion"
    assert captured["api_key"] == "test-internal-key"
    assert captured["body"] == (
        b'{"meetingId":12,"status":"COMPLETED","totalManifests":2,'
        b'"totalSegments":3,"totalChunks":3,"processedChunks":3,'
        b'"succeededChunks":3,"failedChunks":0,"errorMessage":null}'
    )
    await client.close()


@pytest.mark.asyncio
async def test_404_retries_then_succeeds() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(404 if attempts == 1 else 204)

    client = SttCompletionCallbackClient(
        settings(),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    await client.send_completion("python-123", payload())

    assert attempts == 2
    await client.close()


@pytest.mark.asyncio
async def test_500_retries_until_success() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(204 if attempts == 3 else 500)

    client = SttCompletionCallbackClient(
        settings(),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    await client.send_completion("python-123", payload())

    assert attempts == 3
    await client.close()


@pytest.mark.asyncio
async def test_network_error_retries_until_success() -> None:
    attempts = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ConnectError("connection refused", request=request)
        return httpx.Response(204)

    client = SttCompletionCallbackClient(
        settings(),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    await client.send_completion("python-123", payload())

    assert attempts == 2
    await client.close()


@pytest.mark.asyncio
async def test_timeout_retries_until_success() -> None:
    attempts = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ReadTimeout("timeout", request=request)
        return httpx.Response(204)

    client = SttCompletionCallbackClient(
        settings(),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    await client.send_completion("python-123", payload())

    assert attempts == 2
    await client.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("status_code", [400, 401, 409])
async def test_permanent_4xx_does_not_retry(status_code: int) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(status_code)

    client = SttCompletionCallbackClient(
        settings(),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    with pytest.raises(httpx.HTTPStatusError):
        await client.send_completion("python-123", payload())

    assert attempts == 1
    await client.close()


@pytest.mark.asyncio
async def test_retry_exhausted_raises_last_error() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(500)

    client = SttCompletionCallbackClient(
        settings(max_attempts=3),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        sleep=no_sleep,
    )

    with pytest.raises(httpx.HTTPStatusError):
        await client.send_completion("python-123", payload())

    assert attempts == 3
    await client.close()


@pytest.mark.asyncio
async def test_close_closes_async_client() -> None:
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(204)))
    client = SttCompletionCallbackClient(settings(), http_client=http_client, sleep=no_sleep)

    await client.close()

    assert http_client.is_closed
