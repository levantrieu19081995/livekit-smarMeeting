from contextlib import asynccontextmanager
import asyncio
import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.capture import router as capture_router
from app.api.conclusion import router as conclusion_router
from app.api.health import router as health_router
from app.api.transcription import router as transcription_router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.runtime_owner import RuntimeOwner, reconcile_interrupted_captures, validate_runtime_configuration
from app.core.request_limits import RequestLimitsMiddleware
from app.core.runtime_limits import release_runtime_limits
from app.services.capture_manager import get_capture_manager
from app.services.transcription_job_service import get_transcription_job_service


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings)
    validate_runtime_configuration(settings)
    owner = RuntimeOwner(settings.capture_root_dir)
    await asyncio.to_thread(owner.acquire)
    app.state.capture_runtime_owner = owner
    try:
        await asyncio.to_thread(reconcile_interrupted_captures, settings.capture_root_dir)
        service = get_transcription_job_service()
        await service.startup()
        try:
            yield
        finally:
            try:
                await service.shutdown()
            finally:
                await get_capture_manager(settings).stop_all()
    finally:
        manager = get_capture_manager(settings)
        if not getattr(manager, "has_owned_resources", lambda: False)():
            owner.release()
            app.state.capture_runtime_owner = None
        else:
            # Retain the OS handle until process exit. A hot restart must not
            # create another recorder while native cleanup still owns a room.
            logging.getLogger(__name__).critical("Capture cleanup is incomplete; exit this process before restarting")
        get_transcription_job_service.cache_clear()
        release_runtime_limits()


settings = get_settings()
app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.add_middleware(RequestLimitsMiddleware, max_bytes=settings.max_request_body_bytes, body_timeout=settings.request_body_timeout_seconds)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    return JSONResponse(status_code=422, content={"detail": [
        {"loc": error["loc"], "msg": error["msg"], "type": error["type"]} for error in exc.errors()
    ]})


@app.exception_handler(Exception)
async def unexpected_error(request, exc):
    logging.getLogger(__name__).error("Internal service request failed (%s)", type(exc).__name__)
    return JSONResponse(status_code=500, content={"detail": "Internal service operation failed"})
app.include_router(health_router)
app.include_router(conclusion_router)
app.include_router(capture_router)
app.include_router(transcription_router)
