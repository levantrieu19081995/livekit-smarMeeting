from pydantic import BaseModel, Field, field_validator
from urllib.parse import urlsplit


class StartCaptureRequest(BaseModel):
    meetingId: int = Field(ge=1)
    captureSessionId: int = Field(ge=1)
    roomName: str = Field(min_length=1, max_length=255)
    livekitUrl: str = Field(max_length=2048)
    agentIdentity: str = Field(min_length=1, max_length=150)

    @field_validator("roomName", "agentIdentity")
    @classmethod
    def valid_name(cls, value: str) -> str:
        value = value.strip()
        if not value or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value):
            raise ValueError("Capture names must not be blank or contain control characters")
        return value

    @field_validator("livekitUrl")
    @classmethod
    def valid_livekit_url(cls, value: str) -> str:
        if not value: return value
        parsed = urlsplit(value)
        if parsed.scheme not in {"ws", "wss"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            raise ValueError("livekitUrl must be a WebSocket URL without embedded credentials")
        return value

    @property
    def meeting_id(self) -> int:
        return self.meetingId

    @property
    def capture_session_id(self) -> int:
        return self.captureSessionId

    @property
    def room_name(self) -> str:
        return self.roomName

    @property
    def livekit_url(self) -> str:
        return self.livekitUrl

    @property
    def agent_identity(self) -> str:
        return self.agentIdentity


class StopCaptureRequest(BaseModel):
    meetingId: int = Field(ge=1)
    externalSessionId: str = Field(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")

    @property
    def meeting_id(self) -> int:
        return self.meetingId

    @property
    def external_session_id(self) -> str:
        return self.externalSessionId


class CaptureSessionResponse(BaseModel):
    sessionId: str
    status: str
    message: str
    manifestPath: str | None = None
    completedAt: str | None = None


class CaptureStatusResponse(BaseModel):
    captureSessionId: int | None = None
    runtimePresent: bool = False
    sessionId: str | None = None
    status: str
    message: str
    manifestPath: str | None = None
    completedAt: str | None = None


AiServiceSessionResponse = CaptureSessionResponse
AiServiceStatusResponse = CaptureStatusResponse


class HealthResponse(BaseModel):
    status: str
    message: str
