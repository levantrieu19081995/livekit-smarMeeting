import asyncio
import json
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.models.capture import StartCaptureRequest
from app.services import capture_manager as capture_manager_module
from app.services.capture_manager import CaptureManager


class FakeCaptureSession:
    created_count = 0

    def __init__(self, **kwargs) -> None:
        FakeCaptureSession.created_count += 1
        self.session_id = f"new-session-{FakeCaptureSession.created_count}"
        self.status = "STARTING"
        self.capture_session_id = kwargs["capture_session_id"]
        self.kwargs = kwargs

    async def start(self) -> None:
        self.status = "RUNNING"


class BlockingCaptureSession(FakeCaptureSession):
    started = asyncio.Event()
    release = asyncio.Event()

    async def start(self) -> None:
        BlockingCaptureSession.started.set()
        await BlockingCaptureSession.release.wait()
        self.status = "RUNNING"


class FailingCaptureSession(FakeCaptureSession):
    failure = RuntimeError("start failed")

    async def start(self) -> None:
        raise FailingCaptureSession.failure


def manager_with(current, capture_root_dir=None) -> CaptureManager:
    manager = object.__new__(CaptureManager)
    manager.settings = SimpleNamespace(livekit_url="ws://livekit", capture_root_dir=capture_root_dir)
    manager.sessions = {8: current} if current else {}
    manager._lock = asyncio.Lock()
    manager._token_service = object()
    return manager


def request() -> StartCaptureRequest:
    return StartCaptureRequest(
        meetingId=8,
        captureSessionId=100,
        roomName="meeting-8",
        livekitUrl="ws://livekit",
        agentIdentity="CAPTURE_MEETING_8",
    )


@pytest.mark.asyncio
async def test_completed_session_is_replaced_by_a_new_session(monkeypatch) -> None:
    completed = SimpleNamespace(session_id="completed-session", status="COMPLETED")
    manager = manager_with(completed)
    FakeCaptureSession.created_count = 0
    monkeypatch.setattr(capture_manager_module, "CaptureSession", FakeCaptureSession)

    created = await manager.start(request())

    assert created.session_id == "new-session-1"
    assert created.status == "RUNNING"
    assert manager.sessions[8] is created


@pytest.mark.asyncio
async def test_starting_session_is_visible_while_start_is_in_progress(monkeypatch) -> None:
    manager = manager_with(None)
    FakeCaptureSession.created_count = 0
    BlockingCaptureSession.started = asyncio.Event()
    BlockingCaptureSession.release = asyncio.Event()
    monkeypatch.setattr(capture_manager_module, "CaptureSession", BlockingCaptureSession)

    start_task = asyncio.create_task(manager.start(request()))
    await BlockingCaptureSession.started.wait()

    runtime = manager.get(8)
    assert runtime is not None
    assert runtime.capture_session_id == 100
    assert runtime.status == "STARTING"

    BlockingCaptureSession.release.set()
    created = await start_task

    assert created is runtime
    assert created.status == "RUNNING"


@pytest.mark.asyncio
async def test_failed_start_removes_published_runtime(monkeypatch) -> None:
    manager = manager_with(None)
    FakeCaptureSession.created_count = 0
    monkeypatch.setattr(capture_manager_module, "CaptureSession", FailingCaptureSession)

    with pytest.raises(RuntimeError, match="start failed"):
        await manager.start(request())

    assert manager.get(8) is None


@pytest.mark.asyncio
async def test_running_session_remains_idempotent(monkeypatch) -> None:
    running = SimpleNamespace(session_id="running-session", status="RUNNING", capture_session_id=100)
    manager = manager_with(running)
    FakeCaptureSession.created_count = 0
    monkeypatch.setattr(capture_manager_module, "CaptureSession", FakeCaptureSession)

    current = await manager.start(request())

    assert current is running
    assert FakeCaptureSession.created_count == 0


@pytest.mark.asyncio
async def test_running_session_with_different_capture_session_id_is_rejected(monkeypatch) -> None:
    running = SimpleNamespace(session_id="running-session", status="RUNNING", capture_session_id=100)
    manager = manager_with(running)
    FakeCaptureSession.created_count = 0
    monkeypatch.setattr(capture_manager_module, "CaptureSession", FakeCaptureSession)

    different = StartCaptureRequest(
        meetingId=8,
        captureSessionId=101,
        roomName="meeting-8",
        livekitUrl="ws://livekit",
        agentIdentity="CAPTURE_MEETING_8",
    )

    with pytest.raises(capture_manager_module.InvalidSessionError):
        await manager.start(different)

    assert manager.sessions[8] is running
    assert FakeCaptureSession.created_count == 0


def test_correlated_status_exact_runtime() -> None:
    running = SimpleNamespace(
        session_id="abc",
        status="RUNNING",
        capture_session_id=100,
        manifest_relative_path="meeting-8/session-abc/manifest.json",
        completed_at_iso=None,
        active_track_count=lambda: 2,
    )
    manager = manager_with(running)

    response = manager.correlated_status(8, 100, "abc")

    assert response["status"] == "RUNNING"
    assert response["runtimePresent"] is True
    assert response["captureSessionId"] == 100
    assert response["sessionId"] == "abc"


def test_correlated_status_uses_exact_completed_manifest_after_restart() -> None:
    tmp_path = work_dir()
    write_manifest(
        tmp_path,
        meeting_id=8,
        capture_session_id=100,
        session_id="abc",
        status="COMPLETED",
        ended_at="2026-08-21T00:00:00Z",
    )
    manager = manager_with(None, tmp_path)

    response = manager.correlated_status(8, 100, "abc")

    assert response["status"] == "COMPLETED"
    assert response["runtimePresent"] is False
    assert response["sessionId"] == "abc"
    assert response["manifestPath"] == "meeting-8/session-abc/manifest.json"


def test_correlated_status_can_find_manifest_by_capture_session_id_without_external_session() -> None:
    tmp_path = work_dir()
    write_manifest(tmp_path, meeting_id=8, capture_session_id=100, session_id="abc", status="RUNNING")
    manager = manager_with(None, tmp_path)

    response = manager.correlated_status(8, 100, None)

    assert response["status"] == "RUNNING"
    assert response["runtimePresent"] is False
    assert response["sessionId"] == "abc"


def test_correlated_status_does_not_fall_back_to_latest_manifest() -> None:
    tmp_path = work_dir()
    manifest_a = write_manifest(tmp_path, meeting_id=8, capture_session_id=100, session_id="aaa", status="COMPLETED")
    manifest_b = write_manifest(tmp_path, meeting_id=8, capture_session_id=200, session_id="bbb", status="COMPLETED")
    manifest_a.touch()
    manifest_b.touch()
    manager = manager_with(None, tmp_path)

    response = manager.correlated_status(8, 100, None)
    missing = manager.correlated_status(8, 999, None)

    assert response["sessionId"] == "aaa"
    assert response["captureSessionId"] == 100
    assert missing["status"] == "NOT_FOUND"
    assert missing["runtimePresent"] is False


def test_correlated_status_accepts_legacy_manifest_when_external_session_matches() -> None:
    tmp_path = work_dir()
    write_manifest(tmp_path, meeting_id=8, capture_session_id=None, session_id="abc", status="COMPLETED")
    manager = manager_with(None, tmp_path)

    response = manager.correlated_status(8, 100, "abc")

    assert response["status"] == "COMPLETED"
    assert response["captureSessionId"] == 100


def test_correlated_status_rejects_mismatched_manifest_capture_session_id() -> None:
    tmp_path = work_dir()
    write_manifest(tmp_path, meeting_id=8, capture_session_id=101, session_id="abc", status="COMPLETED")
    manager = manager_with(None, tmp_path)

    response = manager.correlated_status(8, 100, "abc")

    assert response["status"] == "NOT_FOUND"


def write_manifest(
    root,
    *,
    meeting_id: int,
    capture_session_id: int | None,
    session_id: str,
    status: str,
    ended_at: str | None = None,
):
    path = root / f"meeting-{meeting_id}" / f"session-{session_id}" / "manifest.json"
    path.parent.mkdir(parents=True)
    data = {
        "meetingId": meeting_id,
        "sessionId": session_id,
        "status": status,
        "endedAt": ended_at,
    }
    if capture_session_id is not None:
        data["captureSessionId"] = capture_session_id
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def work_dir() -> Path:
    path = Path("test_output") / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    return path
