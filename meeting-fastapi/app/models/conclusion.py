from datetime import date
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ConclusionItem(StrictModel):
    content: str = Field(min_length=1, max_length=10000)
    sourceRefs: list[str] = Field(min_length=1, max_length=20)


class ConclusionTask(StrictModel):
    task: str = Field(min_length=1, max_length=5000)
    assignee: str | None = Field(max_length=255)
    deadline: str | None = Field(max_length=10)
    sourceRefs: list[str] = Field(min_length=1, max_length=20)

    @field_validator("deadline")
    @classmethod
    def valid_date(cls, value: str | None) -> str | None:
        if value is not None:
            date.fromisoformat(value)
        return value


class ConclusionResult(StrictModel):
    summary: str = Field(min_length=1, max_length=10000)
    decisions: list[ConclusionItem] = Field(max_length=50)
    pollConclusions: list[ConclusionItem] = Field(max_length=200)
    actionItems: list[ConclusionTask] = Field(max_length=50)
    unresolvedIssues: list[ConclusionItem] = Field(max_length=50)


class SourceText(StrictModel):
    sourceRef: str = Field(min_length=1, max_length=100)
    content: str


class PollOption(StrictModel):
    label: str
    votes: int = Field(ge=0)


class PollSource(StrictModel):
    sourceRef: str = Field(min_length=1, max_length=100)
    question: str
    description: str | None = None
    submitted: int = Field(ge=0)
    eligible: int = Field(ge=0)
    options: list[PollOption] = Field(max_length=20)


class QaSource(StrictModel):
    sourceRef: str = Field(min_length=1, max_length=100)
    question: str
    answer: str | None
    author: str


class OpinionSource(StrictModel):
    sourceRef: str = Field(min_length=1, max_length=100)
    author: str
    content: str


class ConclusionContext(StrictModel):
    meeting: dict[str, Any]
    minutes: SourceText
    polls: list[PollSource] = Field(max_length=200)
    qa: list[QaSource] = Field(max_length=1000)
    opinions: list[OpinionSource] = Field(max_length=1000)
    manual: SourceText


class GenerateConclusionRequest(StrictModel):
    generationId: UUID
    meetingId: int = Field(gt=0)
    context: ConclusionContext


class GenerateConclusionResponse(StrictModel):
    generationId: UUID
    result: ConclusionResult
