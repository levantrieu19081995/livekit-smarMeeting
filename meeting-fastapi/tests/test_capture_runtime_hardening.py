import asyncio
import inspect
import json
import threading
from types import SimpleNamespace

import pytest
from livekit import rtc

from app.core.config import Settings
from app.core.exceptions import InvalidSessionError
from app.services import capture_session as session_module
from app.services import capture_manager as manager_module
from app.services.capture_session import CaptureSession
from app.services.capture_audio_stream import CaptureAudioStream, CaptureFrameQueue, CaptureBufferOverflow
from app.services.segment_recorder import SegmentRecorder
from tests.test_capture_manager import manager_with, request, FakeCaptureSession
from tests.test_segment_recorder import FakeFrame, FakeStream, segment


class ControlledRoom:
    def __init__(self, *, blocked=False):
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.blocked = blocked
        self.connected = False
        self.disconnect_calls = 0
        self.remote_participants = {}
        self.options = None
        self.handlers = {}

    def on(self, event, handler):
        self.handlers[event] = handler

    async def connect(self, url, token, options):
        self.options = options
        self.started.set()
        if self.blocked:
            await self.release.wait()
        self.connected = True

    async def disconnect(self):
        self.disconnect_calls += 1
        self.connected = False


def make_session(tmp_path, monkeypatch, room):
    monkeypatch.setattr(session_module.rtc, "Room", lambda: room)
    return CaptureSession(
        meeting_id=8, capture_session_id=100, room_name="meeting-8",
        livekit_url="ws://localhost:7880", agent_identity="capture-8",
        settings=Settings(capture_root_dir=tmp_path),
        token_service=SimpleNamespace(create_subscribe_only_token=lambda *args: "fake-token"),
    )


def publication(*, microphone=True):
    return SimpleNamespace(
        sid="TR_1", kind=rtc.TrackKind.KIND_AUDIO,
        source=rtc.TrackSource.SOURCE_MICROPHONE if microphone else rtc.TrackSource.SOURCE_SCREENSHARE_AUDIO,
        subscribed=False, set_subscribed=lambda value: None,
    )


@pytest.mark.asyncio
async def test_connect_stop_race_disconnects_late_connection_before_terminal(tmp_path, monkeypatch):
    room = ControlledRoom(blocked=True)
    session = make_session(tmp_path, monkeypatch, room)
    starting = asyncio.create_task(session.start())
    await room.started.wait()
    stopping = asyncio.create_task(session.stop())
    await asyncio.sleep(0)
    assert session.stop_event.is_set()
    assert not session._finalized
    room.release.set()
    await stopping
    with pytest.raises(RuntimeError, match="stopped while connecting"):
        await starting
    assert not room.connected
    assert session._finalized
    assert session.status == "FAILED"
    assert room.disconnect_calls == 1
    assert json.loads((session.session_dir / "manifest.json").read_text())["status"] == "FAILED"


@pytest.mark.asyncio
async def test_capture_explicitly_subscribes_microphones_only(tmp_path, monkeypatch):
    room = ControlledRoom()
    microphone, shared_audio = publication(), publication(microphone=False)
    calls = []
    microphone.set_subscribed = lambda value: calls.append(("microphone", value))
    shared_audio.set_subscribed = lambda value: calls.append(("shared", value))
    room.remote_participants = {"p": SimpleNamespace(track_publications={"mic": microphone, "share": shared_audio})}
    session = make_session(tmp_path, monkeypatch, room)
    await session.start()
    assert room.options.auto_subscribe is False
    assert room.options.connect_timeout == session.settings.capture_connect_timeout_seconds
    assert calls == [("microphone", True)]
    await session.stop()


@pytest.mark.asyncio
async def test_pending_recorder_initializer_cannot_write_after_stop(tmp_path, monkeypatch):
    session = make_session(tmp_path, monkeypatch, ControlledRoom())
    await session.start()
    lock = asyncio.Lock()
    await lock.acquire()
    session._track_locks["TR_1"] = lock
    participant = SimpleNamespace(identity="attendee-1", sid="PA_1", metadata="")
    session._on_track_subscribed(object(), publication(), participant)
    await asyncio.sleep(0)
    assert session._pending_starts
    await session.stop()
    lock.release()
    await asyncio.sleep(0)
    assert not session._pending_starts
    assert not session.recording_tasks
    assert not list(session.session_dir.rglob("*.wav"))
    assert json.loads((session.session_dir / "manifest.json").read_text())["participants"] == []


@pytest.mark.asyncio
async def test_transient_reconnect_and_permanent_disconnect_have_truthful_status(tmp_path, monkeypatch):
    session = make_session(tmp_path, monkeypatch, ControlledRoom())
    await session.start()
    session._on_reconnecting()
    assert session.status == "RUNNING"
    assert "reconnecting" in session.status_message
    session._on_reconnected()
    assert "reconnecting" not in session.status_message
    session._on_disconnected()
    await session._cleanup_task
    assert session.status == "FAILED"
    assert session.completed_at_iso is not None
    await session.stop()
    assert session.status == "FAILED"


@pytest.mark.asyncio
async def test_stop_timeout_blocks_new_capture_until_previous_resources_finish(monkeypatch):
    release = asyncio.Event()
    class SlowSession:
        session_id = "old"
        capture_session_id = 100
        status = "RUNNING"
        async def stop(self, **kwargs):
            self.status = "STOPPING"
            await release.wait()
            self.status = "FAILED" if getattr(self, "_failure_reason", None) else "COMPLETED"
    previous = SlowSession()
    manager = manager_with(previous)
    manager.settings.capture_close_timeout_seconds = 0.005
    monkeypatch.setattr(manager_module, "CaptureSession", FakeCaptureSession)
    with pytest.raises(TimeoutError):
        await manager.stop(8, "old")
    with pytest.raises(InvalidSessionError, match="cleanup"):
        await manager.start(request())
    assert manager.sessions[8] is previous
    release.set()
    await manager._stop_tasks[8]
    created = await manager.start(request())
    assert created is not previous
    assert created.status == "RUNNING"


@pytest.mark.asyncio
async def test_cancelled_start_caller_does_not_cancel_or_orphan_owned_capture(monkeypatch):
    from tests.test_capture_manager import BlockingCaptureSession
    BlockingCaptureSession.started = asyncio.Event()
    BlockingCaptureSession.release = asyncio.Event()
    manager = manager_with(None)
    monkeypatch.setattr(manager_module, "CaptureSession", BlockingCaptureSession)
    caller = asyncio.create_task(manager.start(request()))
    await BlockingCaptureSession.started.wait()
    caller.cancel()
    with pytest.raises(asyncio.CancelledError): await caller
    BlockingCaptureSession.release.set()
    await manager._start_tasks[8]
    await asyncio.sleep(0)
    assert manager.sessions[8].status == "RUNNING"
    assert not manager._start_tasks


@pytest.mark.asyncio
async def test_failed_start_retains_native_ownership_until_disconnect_retry_succeeds(tmp_path, monkeypatch):
    room = ControlledRoom()
    async def failed_connect(*args): raise RuntimeError("connect failed")
    room.connect = failed_connect
    async def failed_disconnect(): raise TimeoutError("disconnect not confirmed")
    room.disconnect = failed_disconnect
    real = make_session(tmp_path, monkeypatch, room)
    manager = manager_with(None)
    manager.settings.capture_terminal_cache_size = 0
    monkeypatch.setattr(manager_module, "CaptureSession", lambda **kwargs: real)
    with pytest.raises(TimeoutError): await manager.start(request())
    assert manager.get(8) is real and real.status == "FAILED"
    assert manager.has_owned_resources()
    with pytest.raises(InvalidSessionError, match="cleanup"): await manager.start(request())
    with pytest.raises(RuntimeError, match="process must exit"): await manager.stop_all()
    assert manager.sessions[8] is real
    room.disconnect = ControlledRoom.disconnect.__get__(room)
    await manager.stop(8, real.session_id)
    assert real._finalized and not manager.has_owned_resources()


@pytest.mark.asyncio
async def test_failed_start_keeps_session_while_concurrent_stop_is_still_draining(monkeypatch):
    started, release_start, draining, release_stop = (asyncio.Event() for _ in range(4))
    class Racing:
        status = "STARTING"
        session_id = "old"
        capture_session_id = 100
        _finalized = False
        async def start(self):
            started.set()
            await release_start.wait()
            self.status = "FAILED"
            raise RuntimeError("start failed")
        async def stop(self, **kwargs):
            draining.set()
            await release_stop.wait()
            self._finalized = True
    real = Racing()
    manager = manager_with(None)
    monkeypatch.setattr(manager_module, "CaptureSession", lambda **kwargs: real)
    starting = asyncio.create_task(manager.start(request()))
    await started.wait()
    stopping = asyncio.create_task(manager.stop(8, "old"))
    await draining.wait()
    release_start.set()
    with pytest.raises(RuntimeError): await starting
    assert manager.get(8) is real
    with pytest.raises(InvalidSessionError): await manager.start(request())
    release_stop.set()
    await stopping


@pytest.mark.asyncio
async def test_frame_queue_drains_before_eos_and_overflow_is_visible():
    queue = CaptureFrameQueue(2)
    queue.put("a")
    queue.put("b")
    queue.put(None)
    assert [await queue.get(), await queue.get(), await queue.get()] == ["a", "b", None]
    overflow = CaptureFrameQueue(2)
    for item in range(1000): overflow.put(item)
    assert len(overflow._frames) == 2
    with pytest.raises(CaptureBufferOverflow): await overflow.get()


def test_pinned_audio_stream_queue_protocol_remains_compatible():
    # SDK upgrades must keep the nonblocking put(frame)/put(None) protocol.
    run = inspect.getsource(rtc.AudioStream._run)
    assert "self._queue.put(event)" in run
    assert "self._queue.put(None)" in run
    assert CaptureAudioStream.__anext__ is not rtc.AudioStream.__anext__


@pytest.mark.asyncio
async def test_slow_disk_is_off_loop_and_bounded_write_overflow_fails(monkeypatch, tmp_path):
    entered, release = threading.Event(), threading.Event()
    loop_thread = threading.get_ident()
    threads = []
    class SlowWave:
        def writeframesraw(self, data):
            threads.append(threading.get_ident())
            entered.set()
            assert release.wait(2)
        def close(self): threads.append(threading.get_ident())
    recorder = SegmentRecorder(
        track=object(), file_path=tmp_path / "slow.wav", segment=segment(),
        sample_rate=48000, num_channels=1, sample_width_bytes=2,
        batch_ms=1, write_queue_capacity=1,
        stream_factory=lambda **kwargs: FakeStream([FakeFrame(b"\x00\x00" * 48, 48)] * 100),
    )
    monkeypatch.setattr(recorder, "_open_wav", lambda: SlowWave())
    task = asyncio.create_task(recorder.record())
    await asyncio.wait_for(asyncio.to_thread(entered.wait, 1), 1.5)
    heartbeat = 0
    for _ in range(10):
        await asyncio.sleep(0.001)
        heartbeat += 1
    assert heartbeat == 10
    assert recorder.segment.status == "FAILED"
    release.set()
    result = await asyncio.wait_for(task, 2)
    assert result.status == "FAILED"
    assert "BUFFER_OVERFLOW" in result.error
    assert threads and all(thread != loop_thread for thread in threads)
