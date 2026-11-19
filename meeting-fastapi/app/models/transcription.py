from datetime import datetime
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field, field_validator


class TranscribeSegmentRequest(BaseModel):
    meetingId: int = Field(ge=1)
    manifestPath: str = Field(min_length=1, max_length=4096)
    segmentId: str = Field(min_length=1, max_length=100)


class TranscribeSegmentResponse(BaseModel):
    meetingId: int
    sessionId: str
    attendeeId: str | None
    speakerName: str | None
    segmentId: str
    segmentIndex: int
    segmentStartedAt: datetime | None
    durationMs: int | None
    filePath: str
    text: str


class TranscribeManifestRequest(BaseModel):
    meetingId: int = Field(ge=1)
    manifestPath: str = Field(min_length=1, max_length=4096)


class TranscribeMeetingRequest(BaseModel):
    requestId: str = Field(min_length=1, max_length=100)
    meetingId: int = Field(ge=1)
    manifestPaths: list[str] = Field(min_length=1, max_length=100)

    @field_validator("requestId")
    @classmethod
    def validate_request_id(cls, value: str) -> str:
        value = value.strip() if value else ""
        if not value:
            raise ValueError("requestId must not be blank")
        if any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value):
            raise ValueError("requestId contains invalid characters")
        return value

    @field_validator("manifestPaths")
    @classmethod
    def validate_manifest_paths(cls, value: list[str]) -> list[str]:
        deduped = []
        seen = set()
        for path in value:
            path = path.strip() if path else ""
            if not path:
                raise ValueError("manifestPaths must not contain blank paths")
            if len(path) > 4096 or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in path):
                raise ValueError("manifestPaths contains an invalid path")
            if path not in seen:
                deduped.append(path)
                seen.add(path)
        return deduped


class TranscriptChunkResult(BaseModel):
    sessionId: str
    attendeeId: str | None
    speakerName: str | None
    segmentId: str
    segmentIndex: int
    chunkIndex: int
    chunkStartMs: int
    chunkDurationMs: int
    segmentStartedAt: datetime | None
    absoluteStartTime: datetime | None
    filePath: str
    text: str


class FailedTranscriptChunk(BaseModel):
    sessionId: str
    segmentId: str
    chunkIndex: int | None
    filePath: str
    attempts: int
    error: str


class TranscribeManifestResponse(BaseModel):
    meetingId: int
    sessionId: str
    status: Literal["COMPLETED", "PARTIAL", "FAILED"]
    totalSegments: int
    totalChunks: int
    processedChunks: int
    succeededChunks: int
    failedChunks: int
    transcripts: list[TranscriptChunkResult]
    failures: list[FailedTranscriptChunk]


class TranscribeMeetingResponse(BaseModel):
    meetingId: int
    sessionIds: list[str]
    status: Literal["COMPLETED", "PARTIAL", "FAILED"]
    totalManifests: int
    totalSegments: int
    totalChunks: int
    processedChunks: int
    succeededChunks: int
    failedChunks: int
    transcripts: list[TranscriptChunkResult]
    failures: list[FailedTranscriptChunk]


class StartTranscriptionJobResponse(BaseModel):
    processingId: str
    meetingId: int
    status: Literal["QUEUED"]


class TranscriptionJobStatusResponse(BaseModel):
    processingId: str
    meetingId: int
    status: Literal["QUEUED", "PROCESSING", "COMPLETED", "PARTIAL", "FAILED"]
    stage: Literal["QUEUED", "TRANSCRIBING", "COMPLETED", "FAILED"]
    processedChunks: int
    totalChunks: int
    progressPercent: float
    error: str | None = None


class TranscriptionJobResultResponse(BaseModel):
    processingId: str
    result: TranscribeMeetingResponse


class SttProgressCallbackPayload(BaseModel):
    meetingId: int
    processedChunks: int
    totalChunks: int
    progressPercent: float


class SttTerminalStatus(str, Enum):
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"


class SttCompletionCallbackPayload(BaseModel):
    meetingId: int
    status: SttTerminalStatus
    totalManifests: int = Field(ge=0)
    totalSegments: int = Field(ge=0)
    totalChunks: int = Field(ge=0)
    processedChunks: int = Field(ge=0)
    succeededChunks: int = Field(ge=0)
    failedChunks: int = Field(ge=0)
    errorMessage: str | None = None
