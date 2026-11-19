import asyncio
import json
from pathlib import Path
from typing import Any
from types import SimpleNamespace

from app.core.config import Settings
from app.core.exceptions import InvalidSessionError, SessionNotFoundError
from app.models.capture import StartCaptureRequest
from app.services.capture_session import CaptureSession
from app.services.livekit_token_service import LiveKitTokenService


class CaptureManager:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.sessions: dict[int, CaptureSession] = {}
        self._lock = asyncio.Lock()
        self._token_service = LiveKitTokenService(settings)
        self._start_tasks: dict[int, asyncio.Task] = {}
        self._stop_tasks: dict[int, asyncio.Task] = {}

    async def start(self, request: StartCaptureRequest) -> CaptureSession:
        self._start_tasks = getattr(self, "_start_tasks", {})
        self._stop_tasks = getattr(self, "_stop_tasks", {})
        async with self._lock:
            self._prune_terminal_sessions()
            current = self.sessions.get(request.meeting_id)
            if current and current.status in {"STARTING", "RUNNING", "STOPPING"}:
                if current.capture_session_id != request.capture_session_id:
                    raise InvalidSessionError(
                        "captureSessionId does not match active capture session"
                    )
                task = self._start_tasks.get(request.meeting_id)
                session = current
            else:
                cleanup = getattr(current, "_cleanup_task", None) if current else None
                pending_start = self._start_tasks.get(request.meeting_id)
                pending_stop = self._stop_tasks.get(request.meeting_id)
                if getattr(current, "_finalized", True) is False or any(task is not None and not task.done() for task in (cleanup, pending_start, pending_stop)):
                    raise InvalidSessionError("Previous capture cleanup is still in progress")
                if sum(s.status in {"STARTING", "RUNNING", "STOPPING"} or getattr(s, "_finalized", True) is False for s in self.sessions.values()) >= getattr(self.settings, "capture_max_active_sessions", 20):
                    raise InvalidSessionError("Active capture session limit reached")
                session = CaptureSession(
                meeting_id=request.meeting_id,
                capture_session_id=request.capture_session_id,
                room_name=request.room_name,
                livekit_url=request.livekit_url or self.settings.livekit_url,
                agent_identity=request.agent_identity,
                settings=self.settings,
                token_service=self._token_service,
                )
                self.sessions[request.meeting_id] = session
                task = asyncio.create_task(session.start())
                self._start_tasks[request.meeting_id] = task
                # A cancelled HTTP caller does not own the capture task's lifetime.
                # Observe its exception and remove it even if no caller remains.
                task.add_done_callback(lambda done, key=request.meeting_id: self._finish_start(key, session, done))
        try:
            if task is not None:
                await asyncio.wait_for(asyncio.shield(task), getattr(self.settings, "capture_connect_timeout_seconds", 15) + 1 + getattr(self.settings, "capture_close_timeout_seconds", 5) * 3)
            return session
        except TimeoutError:
            session.status = "FAILED"
            session._failure_reason = "Capture initialization or cleanup is still in progress"
            raise
        except Exception:
            async with self._lock:
                if getattr(session, "_finalized", True) is not False and self.sessions.get(request.meeting_id) is session:
                    self.sessions.pop(request.meeting_id, None)
            raise
        finally:
            if task is not None and task.done() and self._start_tasks.get(request.meeting_id) is task:
                self._start_tasks.pop(request.meeting_id, None)

    async def stop(self, meeting_id: int, external_session_id: str) -> CaptureSession:
        self._stop_tasks = getattr(self, "_stop_tasks", {})
        async with self._lock:
            session = self.sessions.get(meeting_id)
            if not session:
                observed = await asyncio.to_thread(self._matching_manifest, meeting_id, None, external_session_id)
                if observed and observed[0].get("status") in {"COMPLETED", "FAILED"}:
                    data, path = observed
                    return SimpleNamespace(session_id=external_session_id, status=data["status"], manifest_relative_path=path.relative_to(self.settings.capture_root_dir).as_posix(), completed_at_iso=data.get("endedAt"))
                raise SessionNotFoundError("Capture session not found")
            if session.session_id != external_session_id:
                raise InvalidSessionError("externalSessionId does not match running session")
            task = self._stop_tasks.get(meeting_id)
            if task is None or task.done():
                task = asyncio.create_task(session.stop(expected_session_id=external_session_id))
                self._stop_tasks[meeting_id] = task
        try:
            await asyncio.wait_for(asyncio.shield(task), getattr(self.settings, "capture_close_timeout_seconds", 5) * 3)
            return session
        except TimeoutError:
            session.status = "FAILED"
            session._failure_reason = "Capture cleanup is still in progress"
            raise
        finally:
            if task.done() and self._stop_tasks.get(meeting_id) is task: self._stop_tasks.pop(meeting_id, None)

    def get(self, meeting_id: int) -> CaptureSession | None:
        self._prune_terminal_sessions()
        return self.sessions.get(meeting_id)

    def status(self, meeting_id: int) -> tuple[str, str]:
        session = self.sessions.get(meeting_id)
        if not session:
            return "NOT_FOUND", "Capture session not found"
        if hasattr(session, "status_message"):
            return session.status, session.status_message
        if session.status == "RUNNING":
            return "RUNNING", f"Capture session is running with {session.active_track_count()} active audio tracks"
        return session.status, f"Capture session status is {session.status}"

    def correlated_status(
        self,
        meeting_id: int,
        capture_session_id: int | None,
        external_session_id: str | None,
    ) -> dict[str, Any]:
        session = self._matching_runtime(meeting_id, capture_session_id, external_session_id)
        if session:
            if hasattr(session, "status_message"):
                message = session.status_message
            elif session.status == "RUNNING":
                message = f"Capture session is running with {session.active_track_count()} active audio tracks"
            else:
                message = f"Capture session status is {session.status}"
            return {
                "captureSessionId": session.capture_session_id,
                "runtimePresent": True,
                "sessionId": session.session_id,
                "status": session.status,
                "message": message,
                "manifestPath": session.manifest_relative_path,
                "completedAt": session.completed_at_iso,
            }

        manifest = self._matching_manifest(meeting_id, capture_session_id, external_session_id)
        if manifest:
            data, path = manifest
            manifest_capture_session_id = data.get("captureSessionId")
            return {
                "captureSessionId": manifest_capture_session_id
                if manifest_capture_session_id is not None
                else capture_session_id,
                "runtimePresent": False,
                "sessionId": data.get("sessionId"),
                "status": data.get("status", "NOT_FOUND"),
                "message": "Capture status observed from exact manifest on disk",
                "manifestPath": path.relative_to(self.settings.capture_root_dir).as_posix(),
                "completedAt": data.get("endedAt"),
            }

        return {
            "captureSessionId": capture_session_id,
            "runtimePresent": False,
            "sessionId": external_session_id,
            "status": "NOT_FOUND",
            "message": "Exact capture session not found in runtime or manifest storage",
            "manifestPath": None,
            "completedAt": None,
        }

    async def stop_all(self) -> None:
        sessions = list(self.sessions.items())
        outcomes = await asyncio.gather(*(self.stop(meeting_id, session.session_id) for meeting_id, session in sessions), return_exceptions=True)
        if any(isinstance(outcome, BaseException) for outcome in outcomes):
            raise RuntimeError("Capture resources could not be closed; this process must exit before restarting the worker")
        start_tasks = list(getattr(self, "_start_tasks", {}).values())
        if start_tasks:
            await asyncio.wait_for(asyncio.gather(*start_tasks, return_exceptions=True), getattr(self.settings, "capture_close_timeout_seconds", 5))
        if self.has_owned_resources():
            raise RuntimeError("Capture resources could not be closed; this process must exit before restarting the worker")
        self.sessions.clear()

    def has_owned_resources(self) -> bool:
        return any(getattr(session, "_finalized", True) is False for session in self.sessions.values()) or any(not task.done() for task in list(getattr(self, "_start_tasks", {}).values()) + list(getattr(self, "_stop_tasks", {}).values()))

    def _finish_start(self, meeting_id, session, task) -> None:
        if self._start_tasks.get(meeting_id) is task:
            self._start_tasks.pop(meeting_id, None)
        if not task.cancelled() and task.exception() is not None:
            # Retain failures with in-flight cleanup: they still own resources.
            stopping = self._stop_tasks.get(meeting_id)
            cleanup = getattr(session, "_cleanup_task", None)
            if getattr(session, "_finalized", True) is not False and not any(t is not None and not t.done() for t in (stopping, cleanup)) and self.sessions.get(meeting_id) is session:
                self.sessions.pop(meeting_id, None)

    def _prune_terminal_sessions(self) -> None:
        terminal = [key for key, session in self.sessions.items()
                    if session.status in {"COMPLETED", "FAILED"} and
                    getattr(session, "_finalized", True) is not False and
                    not (getattr(session, "_cleanup_task", None) and not session._cleanup_task.done()) and
                    not (getattr(self, "_start_tasks", {}).get(key) and not self._start_tasks[key].done()) and
                    not (getattr(self, "_stop_tasks", {}).get(key) and not self._stop_tasks[key].done())]
        keep = getattr(self.settings, "capture_terminal_cache_size", 64)
        for key in terminal[:max(0, len(terminal) - keep)]: self.sessions.pop(key, None)

    def latest_manifest_path(self, meeting_id: int) -> Path | None:
        meeting_dir = self.settings.capture_root_dir / f"meeting-{meeting_id}"
        manifests = sorted(meeting_dir.glob("session-*/manifest.json"), key=lambda p: p.stat().st_mtime, reverse=True)
        return manifests[0] if manifests else None

    def latest_manifest(self, meeting_id: int) -> dict[str, Any]:
        path = self.latest_manifest_path(meeting_id)
        if not path:
            raise SessionNotFoundError("Manifest not found")
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)

    def _matching_runtime(
        self,
        meeting_id: int,
        capture_session_id: int | None,
        external_session_id: str | None,
    ) -> CaptureSession | None:
        session = self.sessions.get(meeting_id)
        if not session:
            return None
        if external_session_id and session.session_id != external_session_id:
            return None
        if capture_session_id is not None and session.capture_session_id != capture_session_id:
            return None
        return session

    def _matching_manifest(
        self,
        meeting_id: int,
        capture_session_id: int | None,
        external_session_id: str | None,
    ) -> tuple[dict[str, Any], Path] | None:
        meeting_dir = self.settings.capture_root_dir / f"meeting-{meeting_id}"
        if external_session_id and "/" not in external_session_id and "\\" not in external_session_id:
            candidates = [meeting_dir / f"session-{external_session_id}" / "manifest.json"]
        else:
            candidates = meeting_dir.glob("session-*/manifest.json")
        for path in candidates:
            try:
                with path.open("r", encoding="utf-8") as handle:
                    data = json.load(handle)
            except (OSError, json.JSONDecodeError):
                continue
            if data.get("meetingId") != meeting_id:
                continue
            manifest_session_id = data.get("sessionId")
            manifest_capture_session_id = data.get("captureSessionId")
            if external_session_id:
                if manifest_session_id != external_session_id:
                    continue
                if (
                    capture_session_id is not None
                    and manifest_capture_session_id is not None
                    and manifest_capture_session_id != capture_session_id
                ):
                    continue
                return data, path
            if capture_session_id is not None and manifest_capture_session_id == capture_session_id:
                return data, path
        return None


_manager: CaptureManager | None = None


def get_capture_manager(settings: Settings) -> CaptureManager:
    global _manager
    if _manager is None:
        _manager = CaptureManager(settings)
    return _manager
