package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.livekit.server.WebhookReceiver;
import java.util.List;
import java.util.Optional;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.impl.LiveKitWebhookServiceImpl;

@ExtendWith(MockitoExtension.class)
class LiveKitWebhookServiceTest {

    @Mock private WebhookReceiver webhookReceiver;
    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingParticipantConnectionRepository connectionRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private LiveKitService liveKitService;

    private LiveKitWebhookService webhookService;
    private MeetingParticipantConnectionEntity connection;
    private MeetingParticipantEntity participant;

    @BeforeEach
    void setUp() {
        webhookService = new LiveKitWebhookServiceImpl(webhookReceiver, meetingRepository, connectionRepository, participantRepository, liveKitService);
        participant = participant(ParticipantStatus.INVITED);
        connection = connection(participant, ParticipantConnectionStatus.PENDING);
    }

    @Test
    void missingAuthorizationIsRejectedBeforeMutation() {
        assertThrows(MeetingAccessDeniedException.class, () -> webhookService.handleWebhook(null, "{}"));

        verifyNoInteractions(webhookReceiver, meetingRepository, connectionRepository, participantRepository, liveKitService);
    }

    @Test
    void verifierFailureIsRejectedBeforeMutation() {
        when(webhookReceiver.receive("payload", "bad-token")).thenThrow(new RuntimeException("invalid"));

        assertThrows(MeetingAccessDeniedException.class, () -> webhookService.handleWebhook("bad-token", "payload"));

        verify(webhookReceiver).receive("payload", "bad-token");
        verifyNoInteractions(meetingRepository, connectionRepository, participantRepository, liveKitService);
    }

    @Test
    void pendingInvitedParticipantJoinedUpdatesConnectionAndParticipantByLiveKitIdentity() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.CONNECTED, connection.getStatus());
        assertNotNull(connection.getJoinedAt());
        assertNull(connection.getLeftAt());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());
        assertNotNull(participant.getJoinedAt());
        assertNull(participant.getLeftAt());
        verify(connectionRepository).save(connection);
        verify(participantRepository).save(participant);
    }

    @Test
    void pendingLeftParticipantJoinedUpdatesConnectionAndParticipantByLiveKitIdentity() {
        participant.setStatus(ParticipantStatus.LEFT);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.CONNECTED, connection.getStatus());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());
        assertNotNull(connection.getJoinedAt());
        verify(connectionRepository).save(connection);
        verify(participantRepository).save(participant);
    }

    @Test
    void participantJoinedKicksAndDoesNotDowngradeRemovedParticipant() {
        participant.setStatus(ParticipantStatus.REMOVED);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantStatus.REMOVED, participant.getStatus());
        assertEquals(ParticipantConnectionStatus.REMOVED, connection.getStatus());
        assertNull(connection.getJoinedAt());
        assertNotNull(connection.getLeftAt());
        verify(connectionRepository).save(connection);
        verify(participantRepository, never()).save(participant);
        verify(liveKitService).removeParticipant("meeting-room", "identity-1");
    }

    @Test
    void participantJoinedKicksAndDoesNotDowngradeRemovedConnection() {
        participant.setStatus(ParticipantStatus.JOINED);
        connection.setStatus(ParticipantConnectionStatus.REMOVED);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.REMOVED, connection.getStatus());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());
        verify(connectionRepository).save(connection);
        verify(participantRepository, never()).save(participant);
        verify(liveKitService).removeParticipant("meeting-room", "identity-1");
    }

    @Test
    void unknownIdentityDoesNotMutate() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-unknown"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-unknown")).thenReturn(Optional.empty());

        webhookService.handleWebhook("Bearer token", "payload");

        verify(connectionRepository, never()).save(connection);
        verify(participantRepository, never()).save(participant);
        verifyNoInteractions(liveKitService);
    }

    @Test
    void roomMismatchDoesNotMutate() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "other-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("other-room")).thenReturn(Optional.empty());

        webhookService.handleWebhook("Bearer token", "payload");

        verify(connectionRepository, never()).findByLiveKitIdentity("identity-1");
        verify(connectionRepository, never()).save(connection);
        verify(participantRepository, never()).save(participant);
    }

    @Test
    void participantJoinedLocksMeetingBeforeLoadingConnection() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        InOrder inOrder = inOrder(webhookReceiver, meetingRepository, connectionRepository);
        inOrder.verify(webhookReceiver).receive("payload", "Bearer token");
        inOrder.verify(meetingRepository).findByRoomNameForUpdate("meeting-room");
        inOrder.verify(connectionRepository).findByLiveKitIdentity("identity-1");
        assertEquals(ParticipantConnectionStatus.CONNECTED, connection.getStatus());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());
    }

    @Test
    void verifiedParticipantLifecycleEventWithUnknownRoomDoesNotReadConnection() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "missing-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("missing-room")).thenReturn(Optional.empty());

        webhookService.handleWebhook("Bearer token", "payload");

        verify(connectionRepository, never()).findByLiveKitIdentity("identity-1");
        verify(connectionRepository, never()).save(connection);
        verify(participantRepository, never()).save(participant);
        verifyNoInteractions(liveKitService);
    }

    @Test
    void identityBelongingToDifferentMeetingIsIgnoredAfterMeetingLock() {
        MeetingEntity lockedMeeting = meeting(11L, "meeting-room");
        MeetingParticipantConnectionEntity otherMeetingConnection =
            connection(participant, ParticipantConnectionStatus.PENDING, meeting(12L, "other-meeting-room"), "identity-1");
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(lockedMeeting));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(otherMeetingConnection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.PENDING, otherMeetingConnection.getStatus());
        assertEquals(ParticipantStatus.INVITED, participant.getStatus());
        verify(connectionRepository, never()).save(otherMeetingConnection);
        verify(participantRepository, never()).save(participant);
        verify(liveKitService, never()).removeParticipant("meeting-room", "identity-1");
    }

    @Test
    void participantLeftLeavesParticipantJoinedWhenAnotherConnectionIsConnected() {
        participant.setStatus(ParticipantStatus.JOINED);
        connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        MeetingParticipantConnectionEntity otherConnection = connection(participant, ParticipantConnectionStatus.CONNECTED);
        otherConnection.setId(101L);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        when(connectionRepository.findByParticipantId(31L)).thenReturn(List.of(connection, otherConnection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.LEFT, connection.getStatus());
        assertNotNull(connection.getLeftAt());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());
        verify(connectionRepository).save(connection);
        verify(participantRepository, never()).save(participant);
    }

    @Test
    void participantLeftMarksParticipantLeftWhenNoConnectionRemainsConnected() {
        participant.setStatus(ParticipantStatus.JOINED);
        connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.LEFT, connection.getStatus());
        assertEquals(ParticipantStatus.LEFT, participant.getStatus());
        assertNotNull(participant.getLeftAt());
        verify(connectionRepository).save(connection);
        verify(participantRepository).save(participant);
    }

    @Test
    void abortedConnectionUsesLeftSemantics() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_connection_aborted", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        when(connectionRepository.findByParticipantId(31L)).thenReturn(List.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.LEFT, connection.getStatus());
        assertNull(connection.getJoinedAt());
        assertEquals(ParticipantStatus.LEFT, participant.getStatus());
    }

    @Test
    void removedParticipantIsNotDowngradedOnLeft() {
        participant.setStatus(ParticipantStatus.REMOVED);
        connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));

        webhookService.handleWebhook("Bearer token", "payload");

        assertEquals(ParticipantConnectionStatus.REMOVED, connection.getStatus());
        assertEquals(ParticipantStatus.REMOVED, participant.getStatus());
        assertNotNull(connection.getLeftAt());
        verify(participantRepository, never()).save(participant);
    }

    @Test
    void unsupportedEventDoesNotMutate() {
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("track_published", "meeting-room", "identity-1"));

        webhookService.handleWebhook("Bearer token", "payload");

        verify(meetingRepository, never()).findByRoomNameForUpdate("meeting-room");
        verify(connectionRepository, never()).findByLiveKitIdentity("identity-1");
        verify(connectionRepository, never()).save(connection);
        verify(participantRepository, never()).save(participant);
    }

    @Test
    void twoLeftEventsObserveSerializedConnectionState() {
        participant.setStatus(ParticipantStatus.JOINED);
        MeetingEntity meeting = meeting(11L, "meeting-room");
        MeetingParticipantConnectionEntity connectionA =
            connection(participant, ParticipantConnectionStatus.CONNECTED, meeting, "identity-a");
        MeetingParticipantConnectionEntity connectionB =
            connection(participant, ParticipantConnectionStatus.CONNECTED, meeting, "identity-b");
        when(webhookReceiver.receive("payload-a", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-a"));
        when(webhookReceiver.receive("payload-b", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-b"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(meeting));
        when(connectionRepository.findByLiveKitIdentity("identity-a")).thenReturn(Optional.of(connectionA));
        when(connectionRepository.findByLiveKitIdentity("identity-b")).thenReturn(Optional.of(connectionB));
        when(connectionRepository.findByParticipantId(31L)).thenReturn(List.of(connectionA, connectionB));

        webhookService.handleWebhook("Bearer token", "payload-a");

        assertEquals(ParticipantConnectionStatus.LEFT, connectionA.getStatus());
        assertEquals(ParticipantConnectionStatus.CONNECTED, connectionB.getStatus());
        assertEquals(ParticipantStatus.JOINED, participant.getStatus());

        webhookService.handleWebhook("Bearer token", "payload-b");

        assertEquals(ParticipantConnectionStatus.LEFT, connectionB.getStatus());
        assertEquals(ParticipantStatus.LEFT, participant.getStatus());
        verify(participantRepository).save(participant);
    }

    @Test
    void terminalMeetingLateJoinIsRevokedAndQueuesDurableRemoval() {
        var cleanup = org.mockito.Mockito.mock(MeetingMediaCleanupService.class);
        ((LiveKitWebhookServiceImpl) webhookService).setMediaCleanup(cleanup);
        connection.getMeeting().setStatus(vn.cusc.meetingservice.model.enums.MeetingStatus.COMPLETED);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        webhookService.handleWebhook("Bearer token", "payload");
        assertEquals(ParticipantConnectionStatus.REMOVED, connection.getStatus());
        verify(cleanup).enqueue(11L, "REMOVE_PARTICIPANT", "meeting-room", "identity-1");
        verifyNoInteractions(liveKitService);
        verify(participantRepository, never()).save(participant);
    }

    @Test
    void oldOrEqualSecondJoinCannotUndoLaterLeftEvent() {
        connection.setStatus(ParticipantConnectionStatus.LEFT); connection.setLastWebhookAt(200L);
        participant.setStatus(ParticipantStatus.LEFT);
        when(webhookReceiver.receive("old", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1").toBuilder().setCreatedAt(199).build());
        when(webhookReceiver.receive("equal", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1").toBuilder().setCreatedAt(200).build());
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        webhookService.handleWebhook("Bearer token", "old"); webhookService.handleWebhook("Bearer token", "equal");
        assertEquals(ParticipantConnectionStatus.LEFT, connection.getStatus());
        verify(connectionRepository, never()).save(connection);
    }

    @Test
    void delayedLeftCannotUndoMoreRecentReconnectAndDuplicateJoinKeepsTimestamp() {
        var joined = java.time.LocalDateTime.now().minusMinutes(5);
        connection.setStatus(ParticipantConnectionStatus.CONNECTED); connection.setLastWebhookAt(300L); connection.setJoinedAt(joined);
        participant.setStatus(ParticipantStatus.JOINED); participant.setJoinedAt(joined);
        when(webhookReceiver.receive("left", "Bearer token")).thenReturn(event("participant_left", "meeting-room", "identity-1").toBuilder().setCreatedAt(299).build());
        when(webhookReceiver.receive("join", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1").toBuilder().setCreatedAt(300).build());
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        webhookService.handleWebhook("Bearer token", "left"); webhookService.handleWebhook("Bearer token", "join");
        assertEquals(ParticipantConnectionStatus.CONNECTED, connection.getStatus()); assertEquals(joined, connection.getJoinedAt());
        assertEquals(joined, participant.getJoinedAt()); verify(participantRepository, never()).save(participant);
    }

    @Test
    void changedAccountCredentialEpochCannotReconnectWithOldToken() {
        var account = new vn.cusc.meetingservice.entity.UserAccountEntity();
        account.setAccountStatus(vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE); account.setSecurityVersion(2);
        participant.setUserAccount(account); connection.setAccountSecurityVersion(1L);
        when(webhookReceiver.receive("payload", "Bearer token")).thenReturn(event("participant_joined", "meeting-room", "identity-1"));
        when(meetingRepository.findByRoomNameForUpdate("meeting-room")).thenReturn(Optional.of(connection.getMeeting()));
        when(connectionRepository.findByLiveKitIdentity("identity-1")).thenReturn(Optional.of(connection));
        webhookService.handleWebhook("Bearer token", "payload");
        assertEquals(ParticipantConnectionStatus.REMOVED, connection.getStatus());
        verify(liveKitService).removeParticipant("meeting-room", "identity-1");
    }

    private LivekitWebhook.WebhookEvent event(String event, String roomName, String identity) {
        return LivekitWebhook.WebhookEvent.newBuilder()
            .setEvent(event)
            .setRoom(LivekitModels.Room.newBuilder().setName(roomName).build())
            .setParticipant(LivekitModels.ParticipantInfo.newBuilder().setIdentity(identity).build())
            .build();
    }

    private MeetingParticipantEntity participant(ParticipantStatus status) {
        MeetingParticipantEntity entity = new MeetingParticipantEntity();
        entity.setId(31L);
        entity.setStatus(status);
        return entity;
    }

    private MeetingParticipantConnectionEntity connection(MeetingParticipantEntity participant, ParticipantConnectionStatus status) {
        return connection(participant, status, meeting(11L, "meeting-room"), "identity-1");
    }

    private MeetingParticipantConnectionEntity connection(
        MeetingParticipantEntity participant,
        ParticipantConnectionStatus status,
        MeetingEntity meeting,
        String liveKitIdentity
    ) {
        MeetingParticipantConnectionEntity entity = new MeetingParticipantConnectionEntity();
        entity.setId(41L);
        entity.setMeeting(meeting);
        entity.setParticipant(participant);
        entity.setLiveKitIdentity(liveKitIdentity);
        entity.setStatus(status);
        return entity;
    }

    private MeetingEntity meeting(Long id, String roomName) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(id);
        meeting.setRoomName(roomName);
        return meeting;
    }
}
