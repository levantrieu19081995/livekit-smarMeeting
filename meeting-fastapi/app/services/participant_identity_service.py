import json
from typing import Any

from app.models.manifest import ParticipantCaptureIdentity


class ParticipantIdentityService:
    def from_participant(self, participant: Any) -> ParticipantCaptureIdentity:
        metadata_raw = getattr(participant, "metadata", "") or ""
        metadata: dict[str, Any] = {}
        identity_source = "PARTICIPANT_IDENTITY_FALLBACK"
        if metadata_raw:
            try:
                parsed = json.loads(metadata_raw)
                if isinstance(parsed, dict):
                    metadata = parsed
            except json.JSONDecodeError:
                metadata = {}

        attendee_id = metadata.get("attendeeId")
        if attendee_id:
            identity_source = "ATTENDEE_ID"

        return ParticipantCaptureIdentity(
            attendeeId=attendee_id or getattr(participant, "identity", None),
            connectionId=metadata.get("connectionId"),
            participantIdentity=getattr(participant, "identity", ""),
            participantSid=getattr(participant, "sid", None),
            provider=metadata.get("provider"),
            userId=metadata.get("userId"),
            displayName=metadata.get("displayName"),
            meetingRole=metadata.get("meetingRole"),
            identitySource=identity_source,
        )
