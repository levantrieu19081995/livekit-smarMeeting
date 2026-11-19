import json
import os
import asyncio
import uuid
from pathlib import Path
from typing import Any

from app.models.manifest import ParticipantCaptureIdentity, SegmentMetadata
from app.utils.datetime_utils import iso_utc


class ManifestService:
    def __init__(
        self,
        *,
        session_dir: Path,
        meeting_id: int,
        capture_session_id: int,
        room_name: str,
        session_id: str,
        capture_identity: str,
        sample_rate: int,
        num_channels: int,
        sample_width_bytes: int,
        deferred_writes: bool = False,
        max_events: int = 10000,
        on_write_error=None,
    ) -> None:
        self.session_dir = session_dir
        self.manifest_path = session_dir / "manifest.json"
        self.data: dict[str, Any] = {
            "schemaVersion": "1.0",
            "meetingId": meeting_id,
            "captureSessionId": capture_session_id,
            "roomName": room_name,
            "sessionId": session_id,
            "captureIdentity": capture_identity,
            "status": "RUNNING",
            "sampleRate": sample_rate,
            "numChannels": num_channels,
            "sampleWidthBytes": sample_width_bytes,
            "startedAt": iso_utc(),
            "endedAt": None,
            "participants": [],
            "events": [],
        }
        self._participant_by_attendee: dict[str, dict[str, Any]] = {}
        self._deferred_writes = deferred_writes
        self._max_events = max_events
        self._on_write_error = on_write_error
        self._dirty = False
        self._writer: asyncio.Task | None = None
        self._write_error: Exception | None = None

    def start(self) -> None:
        if not self._deferred_writes:
            self.session_dir.mkdir(parents=True, exist_ok=True)
        self.write()

    def add_participant(self, identity: ParticipantCaptureIdentity) -> None:
        participant = self._get_or_create_participant(identity)
        if identity.participantIdentity not in participant["participantIdentities"]:
            participant["participantIdentities"].append(identity.participantIdentity)
        for field in ("displayName", "provider", "meetingRole"):
            value = getattr(identity, field)
            if value and not participant.get(field):
                participant[field] = value
        self.write()

    def add_segment(self, identity: ParticipantCaptureIdentity, segment: SegmentMetadata) -> None:
        participant = self._get_or_create_participant(identity)
        participant["segments"].append(segment.model_dump())
        self.write()

    def update_segment(self, segment: SegmentMetadata) -> None:
        for participant in self.data["participants"]:
            for index, existing in enumerate(participant["segments"]):
                if existing["segmentId"] == segment.segmentId:
                    participant["segments"][index] = segment.model_dump()
                    self.write()
                    return

    def add_event(self, event_type: str, identity: ParticipantCaptureIdentity | None = None, **extra: Any) -> None:
        event = {
            "type": event_type,
            "attendeeId": identity.attendeeId if identity else None,
            "occurredAt": iso_utc(),
            **extra,
        }
        self.data["events"].append(event)
        if len(self.data["events"]) > self._max_events:
            del self.data["events"][0]
            self.data["droppedEventCount"] = self.data.get("droppedEventCount", 0) + 1
        self.write()

    def finalize(self, status: str = "COMPLETED") -> None:
        self.data["status"] = status
        self.data["endedAt"] = iso_utc()
        self.write()

    def read(self) -> dict[str, Any]:
        with self.manifest_path.open("r", encoding="utf-8") as handle:
            return json.load(handle)

    def list_segments(self) -> list[dict[str, Any]]:
        segments: list[dict[str, Any]] = []
        for participant in self.data["participants"]:
            for segment in participant["segments"]:
                segments.append({"attendeeId": participant["attendeeId"], **segment})
        return segments

    def write(self) -> None:
        if self._deferred_writes:
            self._dirty = True
            if self._write_error is None and (self._writer is None or self._writer.done()):
                self._writer = asyncio.create_task(self._drain_writes())
            return
        self._write_payload(json.dumps(self.data, ensure_ascii=False, indent=2))

    def set_status(self, status: str) -> None:
        self.data["status"] = status
        self.write()

    async def flush(self) -> None:
        if self._writer is not None:
            await asyncio.shield(self._writer)
        if self._write_error is not None:
            raise self._write_error

    def release_memory(self) -> None:
        self.data["participants"] = []
        self.data["events"] = []
        self._participant_by_attendee.clear()

    async def _drain_writes(self) -> None:
        try:
            while self._dirty:
                self._dirty = False
                payload = json.dumps(self.data, ensure_ascii=False, indent=2)
                await asyncio.to_thread(self._write_payload, payload)
        except Exception as exc:
            self._write_error = exc
            if self._on_write_error is not None:
                self._on_write_error()

    def _write_payload(self, payload: str) -> None:
        self.session_dir.mkdir(parents=True, exist_ok=True)
        tmp_path = self.manifest_path.with_suffix(f".{uuid.uuid4().hex}.tmp")
        try:
            with tmp_path.open("w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_path, self.manifest_path)
        finally:
            try: tmp_path.unlink(missing_ok=True)
            except OSError: pass

    def _get_or_create_participant(self, identity: ParticipantCaptureIdentity) -> dict[str, Any]:
        attendee_id = identity.attendeeId or identity.participantIdentity
        if attendee_id in self._participant_by_attendee:
            return self._participant_by_attendee[attendee_id]
        participant = {
            "attendeeId": attendee_id,
            "identitySource": identity.identitySource,
            "participantIdentities": [identity.participantIdentity],
            "displayName": identity.displayName,
            "provider": identity.provider,
            "meetingRole": identity.meetingRole,
            "segments": [],
        }
        self.data["participants"].append(participant)
        self._participant_by_attendee[attendee_id] = participant
        return participant
