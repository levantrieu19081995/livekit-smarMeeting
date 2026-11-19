import asyncio
import logging
import uuid
from pathlib import Path
from typing import Any

from livekit import rtc

from app.core.config import Settings
from app.core.logging import log_context
from app.models.manifest import ParticipantCaptureIdentity, SegmentMetadata
from app.services.livekit_token_service import LiveKitTokenService
from app.services.manifest_service import ManifestService
from app.services.participant_identity_service import ParticipantIdentityService
from app.services.segment_recorder import SegmentRecorder
from app.utils.datetime_utils import iso_utc, utc_now
from app.utils.path_utils import sanitize_path_part

logger = logging.getLogger(__name__)


class CaptureSession:
    def __init__(
        self,
        *,
        meeting_id: int,
        capture_session_id: int,
        room_name: str,
        livekit_url: str,
        agent_identity: str,
        settings: Settings,
        token_service: LiveKitTokenService,
    ) -> None:
        self.meeting_id = meeting_id
        self.capture_session_id = capture_session_id
        self.room_name = room_name
        self.livekit_url = livekit_url or settings.livekit_url
        self.agent_identity = sanitize_path_part(agent_identity, f"AI_MEETING_{meeting_id}")
        self.session_id = str(uuid.uuid4())
        self.status = "STARTING"
        self.started_at = utc_now()
        self.ended_at = None
        self.settings = settings
        self.token_service = token_service
        self.room = rtc.Room()
        self.recording_tasks: dict[str, asyncio.Task[None]] = {}
        self.recorders: dict[str, SegmentRecorder] = {}
        self.track_segments: dict[str, SegmentMetadata] = {}
        self.track_identities: dict[str, ParticipantCaptureIdentity] = {}
        self._track_locks: dict[str, asyncio.Lock] = {}
        self._segment_indexes: dict[str, int] = {}
        self.stop_event = asyncio.Event()
        self.lock = asyncio.Lock()
        self._pending_starts: dict[str, asyncio.Task] = {}
        self._cleanup_task: asyncio.Task | None = None
        self._failure_reason: str | None = None
        self._finalized = False
        self._reconnecting = False
        self._connect_task: asyncio.Task | None = None
        self.identity_service = ParticipantIdentityService()
        self.session_dir = (
            settings.capture_root_dir
            / f"meeting-{meeting_id}"
            / f"session-{self.session_id}"
        )
        self.manifest_service = ManifestService(
            session_dir=self.session_dir,
            meeting_id=meeting_id,
            capture_session_id=capture_session_id,
            room_name=room_name,
            session_id=self.session_id,
            capture_identity=self.agent_identity,
            sample_rate=settings.capture_sample_rate,
            num_channels=settings.capture_num_channels,
            sample_width_bytes=settings.capture_sample_width_bytes,
            deferred_writes=True,
            max_events=settings.capture_max_events,
            on_write_error=lambda: self._mark_failed("Capture manifest persistence failed"),
        )

    async def start(self) -> None:
        self.manifest_service.start()
        self.manifest_service.set_status("STARTING")
        await self.manifest_service.flush()
        self._register_event_handlers()
        token = self.token_service.create_subscribe_only_token(self.room_name, self.agent_identity)
        try:
            async with self.lock:
                if self.stop_event.is_set():
                    raise RuntimeError("Capture was stopped before connecting")
                # Native connect has its own deadline. Do not cancel its FFI waiter:
                # a late successful native connection would otherwise lose its handle.
                self._connect_task = asyncio.create_task(self.room.connect(
                    self.livekit_url, token, rtc.RoomOptions(
                        auto_subscribe=False,
                        connect_timeout=self.settings.capture_connect_timeout_seconds,
                    ),
                ))
                await asyncio.wait_for(asyncio.shield(self._connect_task), self.settings.capture_connect_timeout_seconds + 1)
                if self.stop_event.is_set():
                    raise RuntimeError("Capture was stopped while connecting")
                self._subscribe_existing_tracks()
                self.status = "RUNNING"
                self.manifest_service.set_status("RUNNING")
                await self.manifest_service.flush()
            logger.info("session started %s", self._ctx(event="SESSION_STARTED"))
        except Exception:
            self._failure_reason = "Capture could not connect"
            await self.stop(status="FAILED")
            logger.exception("session failed %s", self._ctx(event="SESSION_FAILED"))
            raise

    async def stop(self, expected_session_id: str | None = None, status: str = "COMPLETED") -> None:
        if expected_session_id and expected_session_id != self.session_id:
            raise ValueError("externalSessionId does not match running session")
        self.stop_event.set()
        async with self.lock:
            if self._finalized:
                return
            if expected_session_id and expected_session_id != self.session_id:
                raise ValueError("externalSessionId does not match running session")
            self.status = "FAILED" if self._failure_reason else "STOPPING"
            try:
                connecting = getattr(self, "_connect_task", None)
                if connecting is not None:
                    # Terminal publication must follow the final disconnect, even
                    # when stop raced with a connect that completed after a timeout.
                    await asyncio.gather(asyncio.shield(connecting), return_exceptions=True)
                starts = list(self._pending_starts.values())
                for task in starts:
                    task.cancel()
                if starts:
                    await asyncio.gather(*starts, return_exceptions=True)
                tasks = list(self.recording_tasks.values())
                for task in tasks:
                    if not task.cancelling():
                        task.cancel()
                if tasks:
                    await asyncio.gather(*tasks, return_exceptions=True)
                await asyncio.wait_for(self.room.disconnect(), self.settings.capture_close_timeout_seconds)
                self.ended_at = utc_now()
                terminal = "FAILED" if self._failure_reason or status == "FAILED" else "COMPLETED"
                self.manifest_service.finalize(terminal)
                await self.manifest_service.flush()
                self.status = terminal
                self._finalized = True
                self._track_locks.clear()
                self._segment_indexes.clear()
                self.manifest_service.release_memory()
                logger.info("session stopped %s", self._ctx(event="SESSION_STOPPED"))
            except Exception:
                self.status = "FAILED"
                self._failure_reason = self._failure_reason or "Capture cleanup failed"
                self.ended_at = utc_now()
                self.manifest_service.finalize("FAILED")
                try:
                    await self.manifest_service.flush()
                except Exception:
                    pass
                logger.exception("session stop failed %s", self._ctx(event="SESSION_STOP_FAILED"))
                raise

    def active_track_count(self) -> int:
        return sum(1 for task in self.recording_tasks.values() if not task.done())

    @property
    def status_message(self) -> str:
        if self._failure_reason:
            return self._failure_reason
        if self._reconnecting:
            return "Capture session is reconnecting; audio may have a gap"
        if self.status == "RUNNING":
            return f"Capture session is running with {self.active_track_count()} active audio tracks"
        return f"Capture session status is {self.status}"

    def _mark_failed(self, message: str) -> None:
        if self._finalized:
            return
        self._failure_reason = self._failure_reason or message
        if self.stop_event.is_set(): return
        self.status = "FAILED"
        self.stop_event.set()
        self.manifest_service.set_status("FAILED")
        self._cleanup_task = asyncio.create_task(self._cleanup_failed())

    async def _cleanup_failed(self) -> None:
        try:
            await self.stop(status="FAILED")
        except Exception:
            logger.error("Capture failure cleanup could not complete meetingId=%s", self.meeting_id)

    def _on_reconnecting(self) -> None:
        if not self.stop_event.is_set():
            self._reconnecting = True
            self.manifest_service.add_event("ROOM_RECONNECTING")

    def _on_reconnected(self) -> None:
        if not self.stop_event.is_set():
            self._reconnecting = False
            self.manifest_service.add_event("ROOM_RECONNECTED")
            self._subscribe_existing_tracks()

    def _on_disconnected(self, reason=None) -> None:
        if not self.stop_event.is_set():
            self.manifest_service.add_event("ROOM_DISCONNECTED")
            self._mark_failed("Capture connection disconnected")

    def _register_event_handlers(self) -> None:
        self.room.on("participant_connected", self._on_participant_connected)
        self.room.on("participant_disconnected", self._on_participant_disconnected)
        self.room.on("track_published", self._on_track_published)
        self.room.on("track_unpublished", self._on_track_unpublished)
        self.room.on("track_subscribed", self._on_track_subscribed)
        self.room.on("track_unsubscribed", self._on_track_unsubscribed)
        self.room.on("track_muted", self._on_track_muted)
        self.room.on("track_unmuted", self._on_track_unmuted)
        self.room.on("reconnecting", self._on_reconnecting)
        self.room.on("reconnected", self._on_reconnected)
        self.room.on("disconnected", self._on_disconnected)

    def _subscribe_existing_tracks(self) -> None:
        participants = getattr(self.room, "remote_participants", {}) or {}
        for participant in participants.values():
            for publication in getattr(participant, "track_publications", {}).values():
                self._subscribe_publication(publication)

    def _on_participant_connected(self, participant: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_participant(identity)
        self.manifest_service.add_event("PARTICIPANT_CONNECTED", identity)
        logger.info("participant connected %s", self._ctx(event="PARTICIPANT_CONNECTED", attendeeId=identity.attendeeId, participantIdentity=identity.participantIdentity))

    def _on_participant_disconnected(self, participant: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_event("PARTICIPANT_DISCONNECTED", identity)
        for publication in getattr(participant, "track_publications", {}).values():
            self._stop_track(getattr(publication, "sid", ""))

    def _on_track_published(self, publication: Any, participant: Any) -> None:
        if self.stop_event.is_set(): return
        self.manifest_service.add_event("TRACK_PUBLISHED", self.identity_service.from_participant(participant), trackSid=publication.sid)
        self._subscribe_publication(publication)

    def _on_track_unpublished(self, publication: Any, participant: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_event("TRACK_UNPUBLISHED", identity, trackSid=publication.sid)
        self._stop_track(publication.sid)

    def _on_track_subscribed(self, track: Any, publication: Any, participant: Any) -> None:
        if self.stop_event.is_set() or not self._is_microphone_audio(publication):
            return
        track_sid = publication.sid
        if track_sid in self._pending_starts: return
        if self.active_track_count() + len(self._pending_starts) >= self.settings.capture_max_audio_tracks:
            self._mark_failed("Capture audio track limit reached")
            return
        task = asyncio.create_task(self._start_recording_track(track, publication, participant))
        self._pending_starts[track_sid] = task
        def completed(done):
            if self._pending_starts.get(track_sid) is done: self._pending_starts.pop(track_sid, None)
            if not done.cancelled() and done.exception() is not None: self._mark_failed("Capture audio initialization failed")
        task.add_done_callback(completed)

    def _on_track_unsubscribed(self, track: Any, publication: Any, participant: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_event("TRACK_UNSUBSCRIBED", identity, trackSid=publication.sid)
        self._stop_track(publication.sid)

    def _on_track_muted(self, participant: Any, publication: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_event("TRACK_MUTED", identity, trackSid=publication.sid)

    def _on_track_unmuted(self, participant: Any, publication: Any) -> None:
        if self.stop_event.is_set(): return
        identity = self.identity_service.from_participant(participant)
        self.manifest_service.add_event("TRACK_UNMUTED", identity, trackSid=publication.sid)

    async def _start_recording_track(self, track: Any, publication: Any, participant: Any) -> None:
        track_sid = publication.sid
        lock = self._track_locks.setdefault(track_sid, asyncio.Lock())
        async with lock:
            if self.stop_event.is_set(): return
            if track_sid in self.recording_tasks and not self.recording_tasks[track_sid].done():
                previous = self.recording_tasks[track_sid]
                if not previous.cancelling(): return
                await asyncio.gather(previous, return_exceptions=True)
                if self.stop_event.is_set(): return
            identity = self.identity_service.from_participant(participant)
            self.manifest_service.add_participant(identity)
            self.track_identities[track_sid] = identity
            attendee_dir = sanitize_path_part(identity.attendeeId, "participant")
            next_index = self._segment_indexes.get(attendee_dir, 0) + 1
            self._segment_indexes[attendee_dir] = next_index
            safe_track_sid = sanitize_path_part(track_sid, "track")
            relative_path = Path("participants") / attendee_dir / f"segment-{next_index:03d}_{safe_track_sid}.wav"
            segment = SegmentMetadata(
                segmentId=str(uuid.uuid4()),
                segmentIndex=next_index,
                connectionId=identity.connectionId,
                participantIdentity=identity.participantIdentity,
                participantSid=identity.participantSid,
                trackSid=track_sid,
                filePath=relative_path.as_posix(),
                startedAt=iso_utc(),
            )
            recorder = SegmentRecorder(
                track=track,
                file_path=self.session_dir / relative_path,
                segment=segment,
                sample_rate=self.settings.capture_sample_rate,
                num_channels=self.settings.capture_num_channels,
                sample_width_bytes=self.settings.capture_sample_width_bytes,
                stream_capacity=self.settings.capture_audio_stream_capacity,
                write_queue_capacity=self.settings.capture_write_queue_capacity,
                batch_ms=self.settings.capture_write_batch_ms,
                close_timeout=self.settings.capture_close_timeout_seconds,
            )
            self.track_segments[track_sid] = segment
            self.recorders[track_sid] = recorder
            self.manifest_service.add_segment(identity, segment)
            task = asyncio.create_task(self._run_recorder(track_sid, recorder))
            self.recording_tasks[track_sid] = task
            logger.info("segment opened %s", self._ctx(event="SEGMENT_OPENED", attendeeId=identity.attendeeId, participantIdentity=identity.participantIdentity, trackSid=track_sid, segmentId=segment.segmentId))

    async def _run_recorder(self, track_sid: str, recorder: SegmentRecorder) -> None:
        try:
            segment = await recorder.record()
            self.manifest_service.update_segment(segment)
            if segment.status == "FAILED": self._mark_failed("Capture audio recorder failed")
        except asyncio.CancelledError:
            segment = recorder.segment
            self.manifest_service.update_segment(segment)
        except Exception as exc:
            segment = recorder.segment
            segment.status = "FAILED"
            segment.error = "CAPTURE_AUDIO_RECORDER_FAILED"
            self.manifest_service.update_segment(segment)
            self._mark_failed("Capture audio recorder failed")
        finally:
            if self.recording_tasks.get(track_sid) is asyncio.current_task():
                self.recording_tasks.pop(track_sid, None)
                self.recorders.pop(track_sid, None)
                self.track_segments.pop(track_sid, None)
                self.track_identities.pop(track_sid, None)
                self._track_locks.pop(track_sid, None)

    def _stop_track(self, track_sid: str) -> None:
        starting = self._pending_starts.get(track_sid)
        if starting and not starting.done() and not starting.cancelling(): starting.cancel()
        task = self.recording_tasks.get(track_sid)
        if task and not task.done() and not task.cancelling():
            task.cancel()

    def _subscribe_publication(self, publication: Any) -> None:
        if not self.stop_event.is_set() and self._is_microphone_audio(publication) and not getattr(publication, "subscribed", False):
            publication.set_subscribed(True)

    def _is_microphone_audio(self, publication: Any) -> bool:
        audio_kind = getattr(rtc.TrackKind, "KIND_AUDIO", None)
        microphone_sources = {
            getattr(rtc.TrackSource, "SOURCE_MICROPHONE", object()),
            getattr(rtc.TrackSource, "MICROPHONE", object()),
        }
        return (
            getattr(publication, "kind", None) == audio_kind
            and getattr(publication, "source", None) in microphone_sources
        )

    @property
    def manifest_relative_path(self) -> str:
        return (Path(f"meeting-{self.meeting_id}") / f"session-{self.session_id}" / "manifest.json").as_posix()

    @property
    def completed_at_iso(self) -> str | None:
        return iso_utc(self.ended_at) if self.ended_at else None

    def _ctx(self, **extra: object) -> str:
        return log_context(meetingId=self.meeting_id, sessionId=self.session_id, roomName=self.room_name, **extra)
