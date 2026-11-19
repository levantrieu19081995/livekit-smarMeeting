import pytest
from fastapi import HTTPException

import app.core.exceptions as exceptions
from app.core.config import Settings
from app.core.exceptions import require_internal_api_key


@pytest.mark.asyncio
async def test_explicit_development_opt_out_allows_missing_key(monkeypatch) -> None:
    monkeypatch.setattr(
        exceptions,
        "get_settings",
        lambda: Settings(app_environment="development", require_internal_api_key=False, internal_api_key="expected-key"),
    )

    await require_internal_api_key(None)


@pytest.mark.asyncio
async def test_require_internal_api_key_enabled_rejects_missing_key(monkeypatch) -> None:
    monkeypatch.setattr(
        exceptions,
        "get_settings",
        lambda: Settings(require_internal_api_key=True, internal_api_key="expected-key"),
    )

    with pytest.raises(HTTPException) as exc_info:
        await require_internal_api_key(None)

    assert exc_info.value.status_code == 401


@pytest.mark.asyncio
async def test_require_internal_api_key_enabled_rejects_wrong_key(monkeypatch) -> None:
    monkeypatch.setattr(
        exceptions,
        "get_settings",
        lambda: Settings(require_internal_api_key=True, internal_api_key="expected-key"),
    )

    with pytest.raises(HTTPException) as exc_info:
        await require_internal_api_key("wrong-key")

    assert exc_info.value.status_code == 401


@pytest.mark.asyncio
async def test_require_internal_api_key_enabled_allows_exact_key(monkeypatch) -> None:
    monkeypatch.setattr(
        exceptions,
        "get_settings",
        lambda: Settings(require_internal_api_key=True, internal_api_key="expected-key"),
    )

    await require_internal_api_key("expected-key")
