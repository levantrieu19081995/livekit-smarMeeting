import asyncio
import json
import struct
import subprocess
import sys
import wave
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.core.config import Settings
from app.core.exceptions import require_internal_api_key
from app.core.request_limits import RequestLimitsMiddleware
from app.core.runtime_owner import RuntimeOwner, reconcile_interrupted_captures, validate_runtime_configuration
from app.models.capture import StartCaptureRequest
from app.models.transcription import TranscribeMeetingRequest
from app.services.capture_manager import CaptureManager


def test_worker_storage_owner_rejects_second_worker_and_releases_after_process_exit(tmp_path):
    code = "from pathlib import Path; from app.core.runtime_owner import RuntimeOwner; import sys; owner=RuntimeOwner(Path(sys.argv[1])); owner.acquire(); print('READY',flush=True); sys.stdin.readline()"
    process = subprocess.Popen([sys.executable, "-B", "-c", code, str(tmp_path)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert process.stdout.readline().strip() == "READY"
        blocked = RuntimeOwner(tmp_path)
        with pytest.raises(RuntimeError, match="one worker"): blocked.acquire()
        process.terminate()
        process.communicate(timeout=5)
        recovered = RuntimeOwner(tmp_path)
        recovered.acquire()
        recovered.release()
        assert (tmp_path / ".meeting-fastapi-owner.lock").exists()
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate(timeout=5)


def test_restart_marks_interrupted_capture_failed_and_repairs_pcm_header(tmp_path):
    folder = tmp_path / "meeting-8" / "session-abc"
    folder.mkdir(parents=True)
    wav = folder / "recording.wav"
    with wave.open(str(wav), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(8000)
        handle.writeframes(b"\x00\x00" * 8000)
    # Model writeframesraw + process death: only the first batch is declared.
    with wav.open("r+b") as handle:
        handle.seek(4)
        handle.write(struct.pack("<I", 36 + 160))
        handle.seek(40)
        handle.write(struct.pack("<I", 160))
    data = {"meetingId": 8, "sessionId": "abc", "captureSessionId": 100,
            "status": "RUNNING", "events": [], "participants": [{"segments": [
                {"segmentId": "seg", "status": "RUNNING", "filePath": "recording.wav"},
                {"segmentId": "missing", "status": "RUNNING", "filePath": "missing.wav"}]}]}
    manifest = folder / "manifest.json"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    assert reconcile_interrupted_captures(tmp_path) == 1
    assert reconcile_interrupted_captures(tmp_path) == 0
    repaired = json.loads(manifest.read_text())
    assert repaired["status"] == "FAILED" and repaired["endedAt"]
    segments = repaired["participants"][0]["segments"]
    assert segments[0]["sampleCount"] == 8000 and segments[0]["byteCount"] == 16000
    assert segments[0]["status"] == "FAILED" and segments[0]["durationMs"] == 1000
    assert segments[1]["error"].endswith("UNRECOVERABLE")
    with wave.open(str(wav), "rb") as handle: assert handle.getnframes() == 8000
    manager = CaptureManager(Settings(capture_root_dir=tmp_path))
    observed = manager.correlated_status(8, 100, "abc")
    assert observed["status"] == "FAILED" and observed["runtimePresent"] is False


def test_restart_repair_never_writes_audio_outside_capture_root(tmp_path):
    root = tmp_path / "capture"
    folder = root / "meeting-8" / "session-abc"
    folder.mkdir(parents=True)
    outside = tmp_path / "outside.wav"
    outside.write_bytes(b"evidence must remain unchanged")
    manifest = folder / "manifest.json"
    manifest.write_text(json.dumps({"status": "STARTING", "participants": [{"segments": [
        {"status": "RUNNING", "filePath": str(outside)}]}]}))
    reconcile_interrupted_captures(root)
    assert outside.read_bytes() == b"evidence must remain unchanged"
    assert json.loads(manifest.read_text())["participants"][0]["segments"][0]["error"].endswith("UNRECOVERABLE")


@pytest.mark.parametrize("overrides", [
    {"require_internal_api_key": False}, {"internal_api_key": ""},
    {"internal_api_key": "dev-ai-key"}, {"livekit_api_key": ""}, {"livekit_api_secret": ""},
])
def test_production_startup_rejects_unsafe_auth_or_missing_service_credentials(overrides):
    values = {"app_environment": "production", "require_internal_api_key": True,
              "internal_api_key": "synthetic-production-key-24-chars", "livekit_api_key": "key", "livekit_api_secret": "secret"}
    values.update(overrides)
    with pytest.raises(RuntimeError): validate_runtime_configuration(Settings(**values))


def test_explicit_development_optout_and_secure_production_configuration():
    validate_runtime_configuration(Settings(app_environment="development", require_internal_api_key=False, internal_api_key=""))
    validate_runtime_configuration(Settings(app_environment="production", require_internal_api_key=True,
                                           internal_api_key="synthetic-production-key-24-chars", livekit_api_key="key", livekit_api_secret="secret"))


@pytest.mark.parametrize("environment,enabled,key,supplied,expected", [
    ("development", False, "", None, None),
    ("test", False, "", None, 503),
    ("production", False, "", None, 503),
    ("test", True, "", None, 503),
    ("test", True, "synthetic", "wrong", 401),
    ("test", True, "synthetic", "synthetic", None),
])
@pytest.mark.asyncio
async def test_internal_auth_is_fail_closed_except_explicit_development(environment, enabled, key, supplied, expected, monkeypatch):
    from app.core import exceptions
    config = Settings(app_environment=environment, require_internal_api_key=enabled, internal_api_key=key)
    monkeypatch.setattr(exceptions, "get_settings", lambda: config)
    if expected is None:
        await require_internal_api_key(x_internal_api_key=supplied)
    else:
        with pytest.raises(HTTPException) as raised: await require_internal_api_key(x_internal_api_key=supplied)
        assert raised.value.status_code == expected


@pytest.mark.parametrize("field,value", [
    ("livekit_url", "https://example.com"), ("livekit_url", "ws://user:secret@example.com"),
    ("MEETING_SERVICE_BASE_URL", "file:///etc/hosts"), ("MEETING_SERVICE_BASE_URL", "https://user:secret@example.com"),
])
def test_service_configuration_rejects_invalid_urls(field, value):
    with pytest.raises(ValidationError): Settings(**{field: value})


def test_api_input_bounds_reject_control_identifiers_and_oversized_lists():
    with pytest.raises(ValidationError):
        TranscribeMeetingRequest(requestId="job\x00key", meetingId=8, manifestPaths=["x"])
    with pytest.raises(ValidationError):
        TranscribeMeetingRequest(requestId="job", meetingId=8, manifestPaths=[str(i) for i in range(101)])
    with pytest.raises(ValidationError):
        StartCaptureRequest(meetingId=8, captureSessionId=100, roomName="room", livekitUrl="ws://user:secret@host", agentIdentity="bot")


async def send_body(frames, *, limit=8, timeout=1, content_length=None, delay=0):
    sent, observed = [], []
    async def app(scope, receive, send):
        observed.append((await receive())["body"])
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})
    index = 0
    async def receive():
        nonlocal index
        if delay: await asyncio.sleep(delay)
        body = frames[index]
        index += 1
        return {"type": "http.request", "body": body, "more_body": index < len(frames)}
    async def send(message): sent.append(message)
    headers = [] if content_length is None else [(b"content-length", content_length.encode())]
    scope = {"type": "http", "method": "POST", "path": "/api/capture", "headers": headers}
    await RequestLimitsMiddleware(app, max_bytes=limit, body_timeout=timeout)(scope, receive, send)
    return sent[0]["status"], observed


@pytest.mark.asyncio
async def test_body_limit_covers_declared_chunked_fragmented_and_slow_inputs():
    assert await send_body([b"123456789"], content_length="9") == (413, [])
    assert await send_body([b"1234", b"56789"]) == (413, [])
    assert await send_body([b""] * 10000 + [b"12345678"]) == (200, [b"12345678"])
    assert await send_body([b"1234"], delay=0.01, timeout=0.001) == (408, [])
    assert await send_body([b"x"], content_length="bad") == (400, [])


@pytest.mark.asyncio
async def test_lifespan_stops_capture_and_releases_owner_even_when_stt_close_fails(tmp_path, monkeypatch):
    from app import main
    calls = []
    class Job:
        async def startup(self): calls.append("startup")
        async def shutdown(self):
            calls.append("stt-close")
            raise RuntimeError("client close failed")
    class Capture:
        async def stop_all(self): calls.append("capture-stop")
        def has_owned_resources(self): return False
    config = Settings(capture_root_dir=tmp_path)
    factory = lambda: Job()
    factory.cache_clear = lambda: None
    monkeypatch.setattr(main, "get_settings", lambda: config)
    monkeypatch.setattr(main, "get_transcription_job_service", factory)
    monkeypatch.setattr(main, "get_capture_manager", lambda _: Capture())
    with pytest.raises(RuntimeError, match="client close failed"):
        async with main.lifespan(main.app): calls.append("running")
    assert calls == ["startup", "running", "stt-close", "capture-stop"]
    owner = RuntimeOwner(tmp_path)
    owner.acquire()
    owner.release()


@pytest.mark.asyncio
async def test_failed_capture_shutdown_retains_owner_until_process_exit(tmp_path, monkeypatch):
    from app import main
    class Job:
        async def startup(self): pass
        async def shutdown(self): pass
    class Capture:
        async def stop_all(self): raise RuntimeError("cleanup incomplete")
        def has_owned_resources(self): return True
    factory = lambda: Job()
    factory.cache_clear = lambda: None
    monkeypatch.setattr(main, "get_settings", lambda: Settings(capture_root_dir=tmp_path))
    monkeypatch.setattr(main, "get_transcription_job_service", factory)
    monkeypatch.setattr(main, "get_capture_manager", lambda _: Capture())
    try:
        with pytest.raises(RuntimeError, match="cleanup incomplete"):
            async with main.lifespan(main.app): pass
        with pytest.raises(RuntimeError, match="one worker"): RuntimeOwner(tmp_path).acquire()
    finally:
        main.app.state.capture_runtime_owner.release()
        main.app.state.capture_runtime_owner = None
