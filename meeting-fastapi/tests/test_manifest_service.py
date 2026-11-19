import json
import uuid
from pathlib import Path

from app.models.manifest import ParticipantCaptureIdentity, SegmentMetadata
from app.services.manifest_service import ManifestService


def work_dir() -> Path:
    path = Path("test_output") / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    return path


def make_service(tmp_path: Path) -> ManifestService:
    service = ManifestService(
        session_dir=tmp_path / "session-1",
        meeting_id=8,
        capture_session_id=100,
        room_name="meeting-x",
        session_id="session-1",
        capture_identity="AI_MEETING_8",
        sample_rate=48000,
        num_channels=1,
        sample_width_bytes=2,
    )
    service.start()
    return service


def identity() -> ParticipantCaptureIdentity:
    return ParticipantCaptureIdentity(
        attendeeId="account:846239",
        connectionId="conn-1",
        participantIdentity="account:846239:conn-1",
        participantSid="PA_1",
        provider="INTERNAL_SSO",
        userId="uuid-1",
        displayName="Le Van Trieu",
        meetingRole="CHAIRMAN",
        identitySource="ATTENDEE_ID",
    )


def test_manifest_session_participant_segments_atomic_and_relative() -> None:
    tmp_path = work_dir()
    service = make_service(tmp_path)
    service.add_participant(identity())
    for index, track_sid in enumerate(("TR_abc", "TR_xyz"), start=1):
        service.add_segment(
            identity(),
            SegmentMetadata(
                segmentId=f"seg-{index}",
                segmentIndex=index,
                participantIdentity="account:846239:conn-1",
                trackSid=track_sid,
                filePath=f"participants/account-846239/segment-{index:03d}_{track_sid}.wav",
                startedAt="2026-08-12T00:00:00Z",
            ),
        )
    loaded = json.loads(service.manifest_path.read_text(encoding="utf-8"))
    assert loaded["meetingId"] == 8
    assert loaded["captureSessionId"] == 100
    assert loaded["sessionId"] == "session-1"
    assert len(loaded["participants"]) == 1
    assert len(loaded["participants"][0]["segments"]) == 2
    assert not Path(loaded["participants"][0]["segments"][0]["filePath"]).is_absolute()
    assert not service.manifest_path.with_suffix(".json.tmp").exists()


def test_segment_error_keeps_manifest_valid() -> None:
    tmp_path = work_dir()
    service = make_service(tmp_path)
    bad_segment = SegmentMetadata(
        segmentId="seg-error",
        segmentIndex=1,
        participantIdentity="p1",
        trackSid="TR_bad",
        filePath="participants/p1/segment-001_TR_bad.wav",
        startedAt="2026-08-12T00:00:00Z",
        status="FAILED",
        error="track failed",
    )
    service.add_segment(identity(), bad_segment)
    loaded = service.read()
    assert loaded["participants"][0]["segments"][0]["error"] == "track failed"
