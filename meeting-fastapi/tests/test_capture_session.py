import asyncio

import pytest
from types import SimpleNamespace

from app.services.capture_session import CaptureSession


class RetryableRoom:
    def __init__(self) -> None:
        self.disconnect_calls = 0

    async def disconnect(self) -> None:
        self.disconnect_calls += 1
        if self.disconnect_calls == 1:
            raise RuntimeError("temporary disconnect failure")


class FakeManifestService:
    def __init__(self) -> None:
        self.finalized_status = None

    def finalize(self, status: str) -> None:
        self.finalized_status = status
    async def flush(self): pass
    def release_memory(self): pass


def stoppable_session() -> CaptureSession:
    session = object.__new__(CaptureSession)
    session.lock = asyncio.Lock()
    session._finalized = False
    session._failure_reason = None
    session._pending_starts = {}
    session._track_locks = {}
    session._segment_indexes = {}
    session.settings = SimpleNamespace(capture_close_timeout_seconds=1)
    session.status = "RUNNING"
    session.session_id = "session-7"
    session.stop_event = asyncio.Event()
    session.recording_tasks = {}
    session.room = RetryableRoom()
    session.ended_at = None
    session.manifest_service = FakeManifestService()
    session.meeting_id = 7
    session.room_name = "meeting-7"
    return session


@pytest.mark.asyncio
async def test_stop_failure_can_be_retried_with_same_session() -> None:
    session = stoppable_session()

    with pytest.raises(RuntimeError, match="temporary disconnect failure"):
        await session.stop(expected_session_id="session-7")

    assert session.status == "FAILED"
    assert session.session_id == "session-7"

    await session.stop(expected_session_id="session-7")

    assert session.status == "FAILED"
    assert session.room.disconnect_calls == 2
    assert session.manifest_service.finalized_status == "FAILED"
