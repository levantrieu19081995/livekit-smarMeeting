import uuid
from dataclasses import dataclass

import pytest
from fastapi.testclient import TestClient

from app.api.capture import manager
from app.main import app


@dataclass
class FakeSession:
    session_id: str
    capture_session_id: int = 100
    status: str = "RUNNING"
    manifest_relative_path: str = "meeting-8/session-test/manifest.json"
    completed_at_iso: str | None = None

    def active_track_count(self) -> int:
        return 2


class FakeManager:
    def __init__(self) -> None:
        self.session = FakeSession(str(uuid.uuid4()))
        self.start_calls = 0

    async def start(self, request):
        self.start_calls += 1
        return self.session

    async def stop(self, meeting_id, external_session_id):
        from app.core.exceptions import InvalidSessionError

        if external_session_id != self.session.session_id:
            raise InvalidSessionError("externalSessionId does not match running session")
        self.session.status = "COMPLETED"
        self.session.completed_at_iso = "2026-08-21T00:00:00Z"
        return self.session

    def get(self, meeting_id):
        return None if meeting_id == 404 else self.session

    def status(self, meeting_id):
        if meeting_id == 404:
            return "NOT_FOUND", "Capture session not found"
        return "RUNNING", "Capture session is running with 2 active audio tracks"

    def correlated_status(self, meeting_id, capture_session_id, external_session_id):
        if (
            meeting_id == 8
            and capture_session_id == self.session.capture_session_id
            and external_session_id == self.session.session_id
        ):
            return {
                "captureSessionId": self.session.capture_session_id,
                "runtimePresent": True,
                "sessionId": self.session.session_id,
                "status": self.session.status,
                "message": "Capture session is running with 2 active audio tracks",
                "manifestPath": self.session.manifest_relative_path,
                "completedAt": self.session.completed_at_iso,
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

    def latest_manifest(self, meeting_id):
        from app.core.exceptions import SessionNotFoundError

        raise SessionNotFoundError("Manifest not found")


@pytest.fixture()
def client():
    fake = FakeManager()
    app.dependency_overrides[manager] = lambda: fake
    with TestClient(app, headers={"X-Internal-Api-Key": "test-internal-key"}) as test_client:
        test_client.fake_manager = fake
        yield test_client
    app.dependency_overrides.clear()


def test_start_new(client) -> None:
    response = client.post(
        "/api/capture/meetings/8/start",
        json={"meetingId": 8, "captureSessionId": 100, "roomName": "meeting-x", "livekitUrl": "ws://livekit", "agentIdentity": "AI_MEETING_8"},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["message"] == "Audio capture started"


def test_start_idempotent_shape(client) -> None:
    payload = {"meetingId": 8, "captureSessionId": 100, "roomName": "meeting-x", "livekitUrl": "ws://livekit", "agentIdentity": "AI_MEETING_8"}
    first = client.post("/api/capture/meetings/8/start", json=payload).json()
    second = client.post("/api/capture/meetings/8/start", json=payload).json()
    assert first["sessionId"] == second["sessionId"]


def test_start_meeting_id_mismatch(client) -> None:
    response = client.post(
        "/api/capture/meetings/9/start",
        json={"meetingId": 8, "captureSessionId": 100, "roomName": "meeting-x", "livekitUrl": "ws://livekit", "agentIdentity": "AI_MEETING_8"},
    )
    assert response.status_code == 400


def test_stop_correct_session(client) -> None:
    session_id = client.fake_manager.session.session_id
    response = client.post("/api/capture/meetings/8/stop", json={"meetingId": 8, "externalSessionId": session_id})
    assert response.status_code == 200
    assert response.json()["status"] == "COMPLETED"
    assert response.json()["manifestPath"] == "meeting-8/session-test/manifest.json"


def test_stop_wrong_external_session_id(client) -> None:
    response = client.post("/api/capture/meetings/8/stop", json={"meetingId": 8, "externalSessionId": "wrong"})
    assert response.status_code == 409


def test_status_not_found(client) -> None:
    response = client.get("/api/capture/meetings/404/status")
    assert response.status_code == 200
    assert response.json()["status"] == "NOT_FOUND"


def test_status_running(client) -> None:
    response = client.get("/api/capture/meetings/8/status")
    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["message"] == "Capture session is running with 2 active audio tracks"


def test_status_exact_runtime(client) -> None:
    session_id = client.fake_manager.session.session_id
    response = client.get(f"/api/capture/meetings/8/status?captureSessionId=100&externalSessionId={session_id}")
    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["runtimePresent"] is True
    assert response.json()["captureSessionId"] == 100
    assert response.json()["sessionId"] == session_id


def test_manifest_not_found(client) -> None:
    response = client.get("/api/capture/meetings/8/manifest")
    assert response.status_code == 404


def test_legacy_start_alias_removed(client) -> None:
    response = client.post(
        "/api/ai/meetings/8/start",
        json={"meetingId": 8, "captureSessionId": 100, "roomName": "meeting-x", "livekitUrl": "ws://livekit", "agentIdentity": "AI_MEETING_8"},
    )
    assert response.status_code == 404


def test_legacy_stop_alias_removed(client) -> None:
    session_id = client.fake_manager.session.session_id
    response = client.post("/api/ai/meetings/8/stop", json={"meetingId": 8, "externalSessionId": session_id})
    assert response.status_code == 404


def test_legacy_status_alias_removed(client) -> None:
    response = client.get("/api/ai/meetings/8/status")
    assert response.status_code == 404
