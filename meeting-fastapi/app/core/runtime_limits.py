import asyncio
import weakref
from dataclasses import dataclass
from contextlib import asynccontextmanager
from contextvars import ContextVar

from app.core.config import Settings


@dataclass
class RuntimeLimits:
    stt: asyncio.Semaphore
    preprocessing: asyncio.Semaphore
    jobs: asyncio.Semaphore


_limits: weakref.WeakKeyDictionary = weakref.WeakKeyDictionary()
_stt_reserved: ContextVar[asyncio.Task | None] = ContextVar("stt_slot_reserved", default=None)


def get_runtime_limits(settings: Settings) -> RuntimeLimits:
    loop = asyncio.get_running_loop()
    current = _limits.get(loop)
    if current is None:
        current = RuntimeLimits(
            stt=asyncio.Semaphore(settings.STT_CONCURRENCY),
            preprocessing=asyncio.Semaphore(settings.STT_PREPROCESS_CONCURRENCY),
            jobs=asyncio.Semaphore(settings.STT_MAX_ACTIVE_JOBS),
        )
        _limits[loop] = current
    return current


@asynccontextmanager
async def stt_execution_slot(settings: Settings):
    owner = asyncio.current_task()
    if _stt_reserved.get() is owner:
        yield
        return
    async with get_runtime_limits(settings).stt:
        token = _stt_reserved.set(owner)
        try:
            yield
        finally:
            _stt_reserved.reset(token)


def release_runtime_limits() -> None:
    _limits.pop(asyncio.get_running_loop(), None)
