package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRoleRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.mapper.MeetingParticipantMapper;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.impl.MeetingParticipantServiceImpl;

@ExtendWith(MockitoExtension.class)
class MeetingParticipantServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private MeetingParticipantConnectionRepository connectionRepository;
    @Mock private UserAccountService userAccountService;
    @Mock private LiveKitService liveKitService;
    @Mock private CurrentUserService currentUserService;

    private MeetingParticipantService participantService;
    private MeetingAccessPolicyService policyService;
    private MeetingEntity meeting;

    @BeforeEach
    void setUp() {
        policyService = new MeetingAccessPolicyService(currentUserService, participantRepository);
        participantService = new MeetingParticipantServiceImpl(
            meetingRepository,
            participantRepository,
            connectionRepository,
            userAccountService,
            liveKitService,
            new MeetingParticipantMapper(),
            policyService
        );
        meeting = meeting();
    }

    @Test
    void authorizedManagerCanListParticipants() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-1", MeetingRole.MEMBER);
        when(meetingRepository.findById(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));

        var response = participantService.getParticipants(11L);

        assertEquals(1, response.size());
        assertEquals("pk-1", response.get(0).participantKey());
    }

    @Test
    void authorizedManagerCanAddParticipant() {
        authorizeCreator();
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(userAccountService.normalizeEmail("new@cusc.vn")).thenReturn("new@cusc.vn");
        when(participantRepository.save(any(MeetingParticipantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = participantService.addParticipant(
            11L,
            new AddMeetingParticipantRequest(null, "new@cusc.vn", "New User", MeetingRole.MEMBER)
        );

        assertEquals("new@cusc.vn", response.email());
        assertEquals(MeetingRole.MEMBER, response.role());
    }

    @Test
    void authorizedManagerCanUpdateParticipant() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-2", MeetingRole.MEMBER);
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));
        when(userAccountService.normalizeEmail("updated@cusc.vn")).thenReturn("updated@cusc.vn");
        when(participantRepository.save(participant)).thenReturn(participant);

        var response = participantService.updateParticipant(
            11L,
            "pk-2",
            new UpdateMeetingParticipantRequest("Updated User", "updated@cusc.vn", MeetingRole.CHAIRMAN)
        );

        assertEquals("Updated User", response.fullName());
        assertEquals(MeetingRole.CHAIRMAN, response.role());
    }

    @Test
    void authorizedManagerCanUpdateParticipantRole() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-3", MeetingRole.MEMBER);
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));
        when(participantRepository.save(participant)).thenReturn(participant);

        var response = participantService.updateParticipantRole(
            11L,
            "pk-3",
            new UpdateMeetingParticipantRoleRequest(MeetingRole.CHAIRMAN)
        );

        assertEquals(MeetingRole.CHAIRMAN, response.role());
    }

    @Test
    void authorizedManagerCanDeleteParticipantAndRemoveFromLiveKit() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-4", MeetingRole.MEMBER);
        UserAccountEntity account = new UserAccountEntity();
        account.setPublicId("account-public");
        participant.setUserAccount(account);
        MeetingParticipantConnectionEntity pending = connection(participant, ParticipantConnectionStatus.PENDING, "identity-pending");
        MeetingParticipantConnectionEntity connected = connection(participant, ParticipantConnectionStatus.CONNECTED, "identity-connected");
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));
        when(connectionRepository.findByParticipantId(21L)).thenReturn(List.of(pending, connected));

        participantService.deleteParticipant(11L, "pk-4");

        assertEquals(ParticipantStatus.REMOVED, participant.getStatus());
        assertEquals(ParticipantConnectionStatus.REMOVED, pending.getStatus());
        assertEquals(ParticipantConnectionStatus.REMOVED, connected.getStatus());
        assertNotNull(pending.getLeftAt());
        assertNotNull(connected.getLeftAt());
        assertEquals("identity-pending", pending.getLiveKitIdentity());
        assertEquals("hash-identity-pending", pending.getChatTokenHash());
        assertEquals("identity-connected", connected.getLiveKitIdentity());
        assertEquals("hash-identity-connected", connected.getChatTokenHash());
        verify(participantRepository).save(participant);
        verify(connectionRepository).saveAll(List.of(pending, connected));
        verify(liveKitService).removeParticipantsByAttendeeId("meeting-room", "account:account-public");
    }

    @Test
    void deleteParticipantLocksMeetingBeforeParticipantLookup() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-lock", MeetingRole.MEMBER);
        MeetingParticipantConnectionEntity pending = connection(participant, ParticipantConnectionStatus.PENDING, "identity-pending");
        MeetingParticipantConnectionEntity connected = connection(participant, ParticipantConnectionStatus.CONNECTED, "identity-connected");
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));
        when(connectionRepository.findByParticipantId(21L)).thenReturn(List.of(pending, connected));

        participantService.deleteParticipant(11L, "pk-lock");

        InOrder inOrder = inOrder(meetingRepository, participantRepository, connectionRepository);
        inOrder.verify(meetingRepository).findByIdForUpdate(11L);
        inOrder.verify(participantRepository).findByMeetingId(11L);
        inOrder.verify(connectionRepository).findByParticipantId(21L);
        assertEquals(ParticipantStatus.REMOVED, participant.getStatus());
        assertEquals(ParticipantConnectionStatus.REMOVED, pending.getStatus());
        assertEquals(ParticipantConnectionStatus.REMOVED, connected.getStatus());
        assertNotNull(pending.getLeftAt());
        assertNotNull(connected.getLeftAt());
    }

    @Test
    void deleteMarksPendingConnectionRemovedSoIssuedTokenCannotBecomeTrusted() {
        authorizeCreator();
        MeetingParticipantEntity participant = participant("pk-5", MeetingRole.MEMBER);
        MeetingParticipantConnectionEntity pending = connection(participant, ParticipantConnectionStatus.PENDING, "identity-pending");
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of(participant));
        when(connectionRepository.findByParticipantId(21L)).thenReturn(List.of(pending));

        participantService.deleteParticipant(11L, "pk-5");

        assertEquals(ParticipantConnectionStatus.REMOVED, pending.getStatus());
        assertNotNull(pending.getLeftAt());
        verify(connectionRepository).saveAll(List.of(pending));
    }

    @Test
    void ordinaryUserCanReadPublicParticipantsWithoutMutationAuthority() {
        authorizeMemberOnly();
        when(meetingRepository.findById(11L)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingId(11L)).thenReturn(List.of());
        assertEquals(0, participantService.getParticipants(11L).size());
        verify(participantRepository).findByMeetingId(11L);
    }

    @Test
    void unauthorizedAuthenticatedParticipantIsDeniedBeforeMutation() {
        authorizeMemberOnly();
        when(meetingRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(meeting));

        assertThrows(MeetingAccessDeniedException.class, () -> participantService.addParticipant(
            11L,
            new AddMeetingParticipantRequest(null, "new@cusc.vn", "New User", MeetingRole.MEMBER)
        ));

        verify(userAccountService, never()).normalizeEmail(any());
        verify(participantRepository, never()).save(any());
    }

    private void authorizeCreator() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal(7L)));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 7L)).thenReturn(Optional.empty());
    }

    private void authorizeMemberOnly() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal(99L)));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 99L)).thenReturn(Optional.of(participant("manager", MeetingRole.MEMBER)));
    }

    private MeetingEntity meeting() {
        MeetingEntity entity = new MeetingEntity();
        entity.setId(11L);
        entity.setRoomName("meeting-room");
        entity.setAccessType(MeetingAccessType.PUBLIC);
        entity.setCreatedByAccount(account(7L));
        return entity;
    }

    private MeetingParticipantEntity participant(String participantKey, MeetingRole role) {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setId(21L);
        participant.setMeeting(meeting);
        participant.setParticipantKey(participantKey);
        participant.setUserId("account-user");
        participant.setInvitedEmailNormalized("user@cusc.vn");
        participant.setFullName("User");
        participant.setEmail("user@cusc.vn");
        participant.setRole(role);
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        return participant;
    }

    private UserAccountEntity account(Long id) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
        return account;
    }

    private MeetingPrincipal principal(Long accountId) {
        return new MeetingPrincipal(
            accountId,
            "public-" + accountId,
            "user-" + accountId,
            "user" + accountId + "@cusc.vn",
            "User " + accountId,
            null,
            AccountStatus.ACTIVE,
            SystemRole.USER,
            true,
            AuthProvider.LOCAL,
            null
        );
    }

    private MeetingParticipantConnectionEntity connection(
        MeetingParticipantEntity participant,
        ParticipantConnectionStatus status,
        String liveKitIdentity
    ) {
        MeetingParticipantConnectionEntity connection = new MeetingParticipantConnectionEntity();
        connection.setMeeting(meeting);
        connection.setParticipant(participant);
        connection.setAttendeeId("account:account-public");
        connection.setConnectionId(liveKitIdentity + "-connection");
        connection.setLiveKitIdentity(liveKitIdentity);
        connection.setJoinedAt(java.time.LocalDateTime.now().minusMinutes(5));
        connection.setStatus(status);
        connection.setChatTokenHash("hash-" + liveKitIdentity);
        return connection;
    }
}
