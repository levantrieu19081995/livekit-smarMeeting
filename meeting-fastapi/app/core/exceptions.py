import secrets

from fastapi import Header, HTTPException, status

from app.core.config import get_settings


class CaptureError(Exception):
    pass


class SessionNotFoundError(CaptureError):
    pass


class SessionConflictError(CaptureError):
    pass


class InvalidSessionError(CaptureError):
    pass


async def require_internal_api_key(x_internal_api_key: str | None = Header(default=None)) -> None:
    settings = get_settings()
    if not settings.require_internal_api_key and settings.app_environment == "development":
        return
    if not settings.require_internal_api_key or not settings.internal_api_key:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Internal authentication is not configured")
    if not x_internal_api_key or not secrets.compare_digest(x_internal_api_key, settings.internal_api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal API key")

