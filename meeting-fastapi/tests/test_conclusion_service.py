import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import HTTPException
from httpx import ASGITransport, AsyncClient

from app.core.config import Settings
from app.models.conclusion import ConclusionResult, GenerateConclusionRequest
from app.services.conclusion_service import ConclusionService


def request(generation_id=None):
    return GenerateConclusionRequest.model_validate({
        "generationId": str(generation_id or uuid4()), "meetingId": 37,
        "context": {"meeting": {"title": "Test", "participants": ["Nguyễn An"]},
                    "minutes": {"sourceRef": "minutes.v1", "content": "Chủ tọa thống nhất kế hoạch."},
                    "manual": {"sourceRef": "manual.pre-ai", "content": "Kết luận đã nhập."},
                    "polls": [{"sourceRef": "poll.1", "question": "Thông qua?", "submitted": 3,
                               "eligible": 4, "options": [{"label": "Tán thành", "votes": 2},
                                                          {"label": "Không tán thành", "votes": 1}]}],
                    "qa": [], "opinions": []},
    })


def result(**changes):
    return {"summary": "Bổ sung kế hoạch.", "decisions": [{"content": "Triển khai kế hoạch.",
            "sourceRefs": ["minutes.v1"]}], "pollConclusions": [], "actionItems": [],
            "unresolvedIssues": [], **changes}


class FakeClient:
    def __init__(self, output=None, error=None, gate=None):
        self.output = output or result()
        self.error = error
        self.gate = gate
        self.calls = []
        self.responses = self

    async def parse(self, **kwargs):
        self.calls.append(kwargs)
        if self.gate is not None:
            await self.gate.wait()
        if self.error:
            raise self.error
        return SimpleNamespace(status="completed", output_parsed=self.output)


def service(tmp_path, client, **kwargs):
    return ConclusionService(Settings(conclusion_root_dir=tmp_path, OPENAI_API_KEY="test-key", **kwargs), client)


@pytest.mark.asyncio
async def test_durable_receipt_avoids_another_provider_call_and_keeps_structured_sources(tmp_path):
    client = FakeClient()
    generator = service(tmp_path, client)
    source = request()
    first = await generator.generate(source)
    assert first == await generator.generate(source)
    replacement = FakeClient()
    assert first == await service(tmp_path, replacement).generate(source)
    assert not replacement.calls
    assert len(client.calls) == 1
    assert client.calls[0]["text_format"] is ConclusionResult
    assert client.calls[0]["store"] is False
    assert first.result.decisions[0].sourceRefs == ["minutes.v1"]
    assert first.result.pollConclusions[0].content == "Thông qua?: 3/4 người đã biểu quyết. Tán thành: 2 phiếu; Không tán thành: 1 phiếu"
    assert source.context.manual.content == "Kết luận đã nhập."


@pytest.mark.asyncio
async def test_concurrent_generation_returns_busy_without_double_call(tmp_path):
    gate = asyncio.Event()
    client = FakeClient(gate=gate)
    generator = service(tmp_path, client)
    source = request()
    running = asyncio.create_task(generator.generate(source))
    while not client.calls:
        await asyncio.sleep(0)
    with pytest.raises(HTTPException) as error:
        await generator.generate(source)
    assert error.value.status_code == 409
    gate.set()
    await running
    assert len(client.calls) == 1


@pytest.mark.asyncio
async def test_same_generation_id_rejects_different_context(tmp_path):
    source = request()
    generator = service(tmp_path, FakeClient())
    await generator.generate(source)
    changed = source.model_copy(deep=True)
    changed.context.minutes.content = "Khác"
    with pytest.raises(HTTPException) as error:
        await generator.generate(changed)
    assert error.value.detail == "CONCLUSION_REQUEST_MISMATCH"


@pytest.mark.asyncio
@pytest.mark.parametrize("output", [
    result(decisions=[{"content": "Invented", "sourceRefs": ["missing"]}]),
    result(actionItems=[{"task": "Task", "assignee": "Invented person", "deadline": None, "sourceRefs": ["minutes.v1"]}]),
    result(actionItems=[{"task": "Task", "assignee": None, "deadline": "2026-12-31", "sourceRefs": ["minutes.v1"]}]),
    {"summary": "Invalid schema"},
])
async def test_invalid_schema_sources_and_unsupported_assignments_are_not_cached(tmp_path, output):
    with pytest.raises(HTTPException) as error:
        await service(tmp_path, FakeClient(output)).generate(request())
    assert error.value.detail == "CONCLUSION_INVALID_RESULT"
    assert not list(tmp_path.glob("*.json"))


@pytest.mark.asyncio
async def test_failure_hides_exception_and_releases_lock_for_retry(tmp_path):
    source = request()
    with pytest.raises(HTTPException) as error:
        await service(tmp_path, FakeClient(error=RuntimeError("secret-key-and-source"))).generate(source)
    assert error.value.detail == "CONCLUSION_PROVIDER_FAILED"
    assert "secret" not in str(error.value)
    assert (await service(tmp_path, FakeClient()).generate(source)).result.summary


@pytest.mark.asyncio
async def test_unconfigured_provider_does_not_consume_generation(tmp_path):
    settings = Settings(conclusion_root_dir=tmp_path, OPENAI_API_KEY="")
    with pytest.raises(HTTPException) as error:
        await ConclusionService(settings).generate(request())
    assert error.value.detail == "CONCLUSION_PROVIDER_NOT_CONFIGURED"
    assert not list(tmp_path.glob("*.json"))


@pytest.mark.asyncio
async def test_internal_api_requires_key_even_when_capture_auth_disabled(tmp_path, monkeypatch):
    from fastapi import FastAPI
    import app.api.conclusion as api
    application = FastAPI()
    application.include_router(api.router)
    settings = Settings(internal_api_key="expected", require_internal_api_key=False)
    monkeypatch.setattr(api, "get_settings", lambda: settings)
    application.dependency_overrides[api.get_conclusion_service] = lambda: service(tmp_path, FakeClient())
    async with AsyncClient(transport=ASGITransport(app=application), base_url="http://test") as client:
        payload = request().model_dump(mode="json")
        assert (await client.post("/api/ai/conclusions/generate", json=payload)).status_code == 401
        assert (await client.post("/api/ai/conclusions/generate", json=payload, headers={"X-Internal-Api-Key": "wrong"})).status_code == 401
        saved = await client.post("/api/ai/conclusions/generate", json=payload, headers={"X-Internal-Api-Key": "expected"})
        assert saved.status_code == 200
        assert saved.json()["result"]["summary"]


@pytest.mark.asyncio
async def test_maximum_supported_poll_preserves_every_option_count(tmp_path):
    source = request()
    poll = source.context.polls[0]
    poll.question = "Q" * 1000
    from app.models.conclusion import PollOption
    poll.options = [PollOption(label=str(index) + "X" * 250, votes=index) for index in range(20)]
    generated = await service(tmp_path, FakeClient()).generate(source)
    content = generated.result.pollConclusions[0].content
    assert len(content) > 5000
    for option in poll.options:
        assert f"{option.label}: {option.votes} phiếu" in content
