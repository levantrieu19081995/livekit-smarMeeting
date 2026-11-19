from pydantic import BaseModel, ConfigDict


class ParticipantCaptureIdentity(BaseModel):
    attendeeId: str | None = None
    connectionId: str | None = None
    participantIdentity: str
    participantSid: str | None = None
    provider: str | None = None
    userId: str | None = None
    displayName: str | None = None
    meetingRole: str | None = None
    identitySource: str

    model_config = ConfigDict(extra="ignore")


class SegmentMetadata(BaseModel):
    segmentId: str
    segmentIndex: int
    connectionId: str | None = None
    participantIdentity: str
    participantSid: str | None = None
    trackSid: str
    trackSource: str = "MICROPHONE"
    filePath: str
    startedAt: str
    endedAt: str | None = None
    durationMs: int | None = None
    frameCount: int = 0
    sampleCount: int = 0
    byteCount: int = 0
    status: str = "RUNNING"
    error: str | None = None
