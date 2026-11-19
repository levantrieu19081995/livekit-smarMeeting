from dataclasses import dataclass

from app.services.participant_identity_service import ParticipantIdentityService


@dataclass
class Participant:
    identity: str
    sid: str = "PA_1"
    metadata: str = ""


def test_metadata_sso_valid() -> None:
    identity = ParticipantIdentityService().from_participant(
        Participant("conn", metadata='{"attendeeId":"account:846239","connectionId":"c1","provider":"INTERNAL_SSO","userId":"uuid-1","displayName":"Le Van Trieu","meetingRole":"CHAIRMAN"}')
    )
    assert identity.attendeeId == "account:846239"
    assert identity.identitySource == "ATTENDEE_ID"
    assert identity.meetingRole == "CHAIRMAN"


def test_metadata_google_valid() -> None:
    identity = ParticipantIdentityService().from_participant(
        Participant("google-user", metadata='{"attendeeId":"account:abc","provider":"GOOGLE","meetingRole":"MEMBER"}')
    )
    assert identity.attendeeId == "account:abc"
    assert identity.provider == "GOOGLE"


def test_metadata_guest_valid() -> None:
    identity = ParticipantIdentityService().from_participant(
        Participant("guest-1", metadata='{"attendeeId":"guest:a81f","provider":"GUEST","displayName":"Guest","meetingRole":"GUEST"}')
    )
    assert identity.attendeeId == "guest:a81f"


def test_bad_metadata_falls_back() -> None:
    identity = ParticipantIdentityService().from_participant(Participant("user-1", metadata="{bad"))
    assert identity.attendeeId == "user-1"
    assert identity.identitySource == "PARTICIPANT_IDENTITY_FALLBACK"


def test_missing_attendee_id_falls_back_to_participant_identity() -> None:
    identity = ParticipantIdentityService().from_participant(Participant("user-2", metadata='{"displayName":"A"}'))
    assert identity.attendeeId == "user-2"
    assert identity.identitySource == "PARTICIPANT_IDENTITY_FALLBACK"


def test_two_connections_same_attendee_group_key() -> None:
    service = ParticipantIdentityService()
    first = service.from_participant(Participant("conn-1", metadata='{"attendeeId":"account:1","connectionId":"c1"}'))
    second = service.from_participant(Participant("conn-2", metadata='{"attendeeId":"account:1","connectionId":"c2"}'))
    assert first.attendeeId == second.attendeeId == "account:1"
    assert first.connectionId != second.connectionId
