import asyncio
import threading
from contextvars import ContextVar


class BlockingOperationCancelled(Exception):
    pass


_cancellation: ContextVar[threading.Event | None] = ContextVar("blocking_cancellation", default=None)


def check_cancelled() -> None:
    event = _cancellation.get()
    if event is not None and event.is_set():
        raise BlockingOperationCancelled("Blocking work cancelled")


async def run_cancellable_cpu(function, *args, **kwargs):
    event = threading.Event()
    token = _cancellation.set(event)
    try:
        task = asyncio.create_task(asyncio.to_thread(function, *args, **kwargs))
    finally:
        _cancellation.reset(token)
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        event.set()
        try:
            await task
        except BlockingOperationCancelled:
            pass
        raise
