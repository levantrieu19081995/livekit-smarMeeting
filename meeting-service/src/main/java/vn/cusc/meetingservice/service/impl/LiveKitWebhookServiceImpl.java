package vn.cusc.meetingservice.service.impl;

import java.time.LocalDateTime;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.LiveKitWebhookService;

@Service
public class LiveKitWebhookServiceImpl implements LiveKitWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookServiceImpl.class);

    private final WebhookReceiver webhookReceiver;
    private final MeetingRepository meetingRepository;
    private final MeetingParticipantConnectionRepository connectionRepository;
    private final MeetingParticipantRepository participantRepository;
    private final LiveKitService liveKitService;
    private vn.cusc.meetingservice.service.MeetingMediaCleanupService mediaCleanup;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaCleanup(vn.cusc.meetingservice.service.MeetingMediaCleanupService cleanup) { mediaCleanup = cleanup; }

    public LiveKitWebhookServiceImpl(WebhookReceiver webhookReceiver,
                                     MeetingRepository meetingRepository,
                                     MeetingParticipantConnectionRepository connectionRepository,
                                     MeetingParticipantRepository participantRepository,
                                     LiveKitService liveKitService) {
        this.webhookReceiver = webhookReceiver;
        this.meetingRepository = meetingRepository;
        this.connectionRepository = connectionRepository;
        this.participantRepository = participantRepository;
        this.liveKitService = liveKitService;
    }

    @Override
    @Transactional
    public void handleWebhook(String authorization, String payload) {
        if (authorization == null || authorization.isBlank()) {
            throw new MeetingAccessDeniedException("INVALID_LIVEKIT_WEBHOOK");
        }

        LivekitWebhook.WebhookEvent event;
        try {
            event = webhookReceiver.receive(payload, authorization);
        } catch (Exception exception) {
            throw new MeetingAccessDeniedException("INVALID_LIVEKIT_WEBHOOK");
        }

        if (!event.hasParticipant()) {
            return;
        }
        String identity = event.getParticipant().getIdentity();
        if (identity == null || identity.isBlank()) {
            return;
        }

        if (!isParticipantLifecycleEvent(event.getEvent())) {
            return;
        }
        String roomName = event.hasRoom() ? event.getRoom().getName() : null;
        if (roomName == null || roomName.isBlank()) {
            log.warn("Ignoring LiveKit webhook without room name. event={}, identity={}", event.getEvent(), identity);
            return;
        }

        MeetingEntity lockedMeeting = meetingRepository.findByRoomNameForUpdate(roomName).orElse(null);
        if (lockedMeeting == null) {
            log.warn("Ignoring LiveKit webhook for unknown room. event={}, roomName={}, identity={}",
                event.getEvent(), roomName, identity);
            if ("participant_joined".equals(event.getEvent()) && roomName.startsWith("meeting-") && mediaCleanup != null)
                mediaCleanup.enqueue(null, "REMOVE_PARTICIPANT", roomName, identity);
            return;
        }

        MeetingParticipantConnectionEntity connection = connectionRepository.findByLiveKitIdentity(identity).orElse(null);
        if (connection == null) {
            return;
        }
        if (!lockedMeeting.getId().equals(connection.getMeeting().getId())) {
            log.warn(
                "Ignoring LiveKit webhook with identity/room mismatch. roomName={}, identity={}, lockedMeetingId={}, connectionMeetingId={}",
                roomName,
                identity,
                lockedMeeting.getId(),
                connection.getMeeting().getId()
            );
            return;
        }

        long eventTime = event.getCreatedAt();
        if (eventTime > 0 && connection.getLastWebhookAt() != null
            && (eventTime < connection.getLastWebhookAt() || eventTime == connection.getLastWebhookAt()
                && "participant_joined".equals(event.getEvent()) && connection.getStatus() == ParticipantConnectionStatus.LEFT)) return;
        if (eventTime > 0) connection.setLastWebhookAt(eventTime);

        if ("participant_joined".equals(event.getEvent())) {
            handleParticipantJoined(connection);
            return;
        }

        if ("participant_left".equals(event.getEvent()) || "participant_connection_aborted".equals(event.getEvent())) {
            handleParticipantTerminated(connection);
        }
    }

    private boolean isParticipantLifecycleEvent(String event) {
        return "participant_joined".equals(event)
            || "participant_left".equals(event)
            || "participant_connection_aborted".equals(event);
    }

    private void handleParticipantJoined(MeetingParticipantConnectionEntity connection) {
        LocalDateTime now = LocalDateTime.now();
        MeetingParticipantEntity participant = connection.getParticipant();
        var effective = new vn.cusc.meetingservice.util.MeetingStatusResolver().resolve(connection.getMeeting());
        if (participant.getStatus() == ParticipantStatus.REMOVED
            || connection.getStatus() == ParticipantConnectionStatus.REMOVED
            || effective == vn.cusc.meetingservice.model.enums.MeetingStatus.COMPLETED
            || effective == vn.cusc.meetingservice.model.enums.MeetingStatus.CANCELLED
            || participant.getUserAccount() != null && !vn.cusc.meetingservice.security.AccountCredentialVersions.matches(connection, participant.getUserAccount())) {
            connection.setStatus(ParticipantConnectionStatus.REMOVED);
            connection.setLeftAt(now);
            connectionRepository.save(connection);
            try {
                if (mediaCleanup != null) mediaCleanup.enqueue(connection.getMeeting().getId(), "REMOVE_PARTICIPANT",
                    connection.getMeeting().getRoomName(), connection.getLiveKitIdentity());
                else liveKitService.removeParticipant(
                    connection.getMeeting().getRoomName(),
                    connection.getLiveKitIdentity()
                );
            } catch (LiveKitOperationException exception) {
                log.warn(
                    "Failed to remove LiveKit participant for removed connection. roomName={}, liveKitIdentity={}",
                    connection.getMeeting().getRoomName(),
                    connection.getLiveKitIdentity(),
                    exception
                );
            }
            return;
        }

        if (connection.getStatus() == ParticipantConnectionStatus.CONNECTED) {
            // Delivery can be retried. Do not rewrite the attendance timestamp of an existing connection.
            connectionRepository.save(connection);
            return;
        }
        connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        connection.setJoinedAt(now);
        connection.setLeftAt(null);
        connectionRepository.save(connection);

        participant.setStatus(ParticipantStatus.JOINED);
        participant.setJoinedAt(now);
        participant.setLeftAt(null);
        participantRepository.save(participant);
    }

    private void handleParticipantTerminated(MeetingParticipantConnectionEntity connection) {
        LocalDateTime now = LocalDateTime.now();
        MeetingParticipantEntity participant = connection.getParticipant();
        if (connection.getStatus() == ParticipantConnectionStatus.LEFT) {
            connectionRepository.save(connection);
            return;
        }
        if (participant.getStatus() == ParticipantStatus.REMOVED
            || connection.getStatus() == ParticipantConnectionStatus.REMOVED) {
            connection.setStatus(ParticipantConnectionStatus.REMOVED);
            connection.setLeftAt(now);
            connectionRepository.save(connection);
            return;
        }

        connection.setStatus(ParticipantConnectionStatus.LEFT);
        connection.setLeftAt(now);
        connectionRepository.save(connection);

        boolean hasConnectedConnection = connectionRepository.findByParticipantId(participant.getId()).stream()
            .anyMatch(item -> item.getStatus() == ParticipantConnectionStatus.CONNECTED);
        if (!hasConnectedConnection) {
            participant.setStatus(ParticipantStatus.LEFT);
            participant.setLeftAt(now);
            participantRepository.save(participant);
        }
    }
}
