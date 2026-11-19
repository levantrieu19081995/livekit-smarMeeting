package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.config.FrontendProperties;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.mapper.MeetingMapper;
import vn.cusc.meetingservice.mapper.MeetingParticipantMapper;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.impl.MeetingServiceImpl;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;
import vn.cusc.meetingservice.util.MeetingCodeGenerator;
import vn.cusc.meetingservice.util.MeetingStatusResolver;
import vn.cusc.meetingservice.util.RoomNameGenerator;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingParticipantRepository meetingParticipantRepository;
    @Mock private MeetingParticipantConnectionRepository connectionRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private UserAccountService userAccountService;
    @Mock private CurrentUserService currentUserService;
    @Mock private LiveKitService liveKitService;
    @Mock private MeetingAccessPolicyService meetingAccessPolicyService;
    @Mock private MeetingCaptureService meetingCaptureService;
    @Mock private MeetingPollTerminalService meetingPollTerminalService;

    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        FrontendProperties frontendProperties = new FrontendProperties();
        frontendProperties.setBaseUrl("http://localhost:3000");
        LiveKitProperties liveKitProperties = new LiveKitProperties();
        liveKitProperties.setUrl("ws://172.16.20.177:7880");

        meetingService = new MeetingServiceImpl(
            meetingRepository,
            meetingParticipantRepository,
            connectionRepository,
            userAccountRepository,
            userAccountService,
            currentUserService,
            new MeetingCodeGenerator(),
            new RoomNameGenerator(),
            liveKitService,
            new MeetingMapper(frontendProperties, new MeetingParticipantMapper()),
            new MeetingStatusResolver(),
            liveKitProperties,
            meetingAccessPolicyService,
            meetingCaptureService,
            meetingPollTerminalService
        );
    }

    @Test
    void createMeetingRejectsDuplicateParticipants() {
        CreateMeetingRequest request = new CreateMeetingRequest(
            "Meeting",
            "Desc",
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(2),
            MeetingAccessType.PRIVATE,
            List.of(
                new AddMeetingParticipantRequest("uuid-1", null, "A", MeetingRole.CHAIRMAN),
                new AddMeetingParticipantRequest("uuid-1", null, "B", MeetingRole.MEMBER)
            )
        );

        assertThrows(BusinessValidationException.class, () -> meetingService.createMeeting(request));
        verify(liveKitService, never()).createRoom(any());
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void createMeetingRejectsMultipleSecretariesBeforeAllocatingLiveKitOrSaving(MeetingAccessType accessType) {
        var request = new CreateMeetingRequest("Meeting", null, LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1), accessType, List.of(
                new AddMeetingParticipantRequest("chair", null, "Chair", MeetingRole.CHAIRMAN),
                new AddMeetingParticipantRequest("secretary-1", null, "Secretary One", MeetingRole.SECRETARY),
                new AddMeetingParticipantRequest("secretary-2", null, "Secretary Two", MeetingRole.SECRETARY),
                new AddMeetingParticipantRequest("member", null, "Member", MeetingRole.MEMBER)));

        var exception = assertThrows(BusinessValidationException.class, () -> meetingService.createMeeting(request));

        assertEquals("Chỉ được có 1 thư ký trong cuộc họp.", exception.getMessage());
        verify(liveKitService, never()).createRoom(any());
        verify(liveKitService, never()).deleteRoom(any());
        verify(meetingRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void createMeetingPreservesChairSecretaryAndMemberRoles(MeetingAccessType accessType) {
        authorizeCreator();
        when(userAccountService.normalizeEmail(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.createRoom(any())).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 0, 0L));
        when(meetingRepository.save(any(MeetingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new CreateMeetingRequest("Meeting", null, LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1), accessType, List.of(
                new AddMeetingParticipantRequest(null, "chair@cusc.test", "Chair", MeetingRole.CHAIRMAN),
                new AddMeetingParticipantRequest(null, "secretary@cusc.test", "Secretary", MeetingRole.SECRETARY),
                new AddMeetingParticipantRequest(null, "member-1@cusc.test", "Member One", MeetingRole.MEMBER),
                new AddMeetingParticipantRequest(null, "member-2@cusc.test", "Member Two", MeetingRole.MEMBER)));

        meetingService.createMeeting(request);

        var saved = ArgumentCaptor.forClass(MeetingEntity.class);
        verify(meetingRepository).save(saved.capture());
        assertEquals(accessType == MeetingAccessType.PRIVATE ? List.of(MeetingRole.CHAIRMAN, MeetingRole.SECRETARY, MeetingRole.MEMBER, MeetingRole.MEMBER, MeetingRole.MEMBER) : List.of(MeetingRole.CHAIRMAN, MeetingRole.SECRETARY, MeetingRole.MEMBER, MeetingRole.MEMBER),
            saved.getValue().getParticipants().stream().map(MeetingParticipantEntity::getRole).toList());
        verify(liveKitService).createRoom(any());
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void createMeetingDoesNotRequireSecretary(MeetingAccessType accessType) {
        authorizeCreator();
        when(userAccountService.normalizeEmail(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.createRoom(any())).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 0, 0L));
        when(meetingRepository.save(any(MeetingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new CreateMeetingRequest("Meeting", null, LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1), accessType, List.of(
                new AddMeetingParticipantRequest(null, "chair@cusc.test", "Chair", MeetingRole.CHAIRMAN),
                new AddMeetingParticipantRequest(null, "member@cusc.test", "Member", MeetingRole.MEMBER)));

        meetingService.createMeeting(request);

        var saved = ArgumentCaptor.forClass(MeetingEntity.class);
        verify(meetingRepository).save(saved.capture());
        assertEquals(accessType == MeetingAccessType.PRIVATE ? List.of(MeetingRole.CHAIRMAN, MeetingRole.MEMBER, MeetingRole.MEMBER) : List.of(MeetingRole.CHAIRMAN, MeetingRole.MEMBER),
            saved.getValue().getParticipants().stream().map(MeetingParticipantEntity::getRole).toList());
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void createMeetingRetainsSingleChairmanRule(MeetingAccessType accessType) {
        var request = new CreateMeetingRequest("Meeting", null, LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1), accessType, List.of(
                new AddMeetingParticipantRequest("chair-1", null, "Chair One", MeetingRole.CHAIRMAN),
                new AddMeetingParticipantRequest("chair-2", null, "Chair Two", MeetingRole.CHAIRMAN)));

        var exception = assertThrows(BusinessValidationException.class, () -> meetingService.createMeeting(request));

        assertEquals("Chi duoc co 1 chairman", exception.getMessage());
        verify(liveKitService, never()).createRoom(any());
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void createMeetingWithPastScheduleDoesNotPersistEffectiveCompleted() {
        authorizeCreator();
        CreateMeetingRequest request = new CreateMeetingRequest(
            "Meeting",
            "Desc",
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingAccessType.PUBLIC,
            null
        );
        when(liveKitService.createRoom(any())).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 0, 0L));
        when(meetingRepository.save(any(MeetingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = meetingService.createMeeting(request);

        assertEquals(MeetingStatus.COMPLETED, response.status());
        ArgumentCaptor<MeetingEntity> meetingCaptor = ArgumentCaptor.forClass(MeetingEntity.class);
        verify(meetingRepository).save(meetingCaptor.capture());
        assertEquals(MeetingStatus.UPCOMING, meetingCaptor.getValue().getStatus());
    }

    @Test
    void joinMeetingRejectsAnonymousPrivateMeeting() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PRIVATE);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        assertThrows(MeetingAccessDeniedException.class, () ->
            meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, null, "Guest User")));
    }

    @Test
    void joinMeetingRejectsPersistedCompletedMeetingBeforeRealtimeWork() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.COMPLETED);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () ->
            meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User")));

        assertEquals("Cuộc họp đã kết thúc hoặc đã bị hủy", exception.getMessage());
        verify(currentUserService, never()).getCurrentPrincipal();
        verify(liveKitService, never()).ensureRoomExists(any());
        verify(liveKitService, never()).generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void joinMeetingRejectsCancelledMeetingBeforeRealtimeWork() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.CANCELLED);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () ->
            meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User")));

        assertEquals("Cuộc họp đã kết thúc hoặc đã bị hủy", exception.getMessage());
        verify(liveKitService, never()).ensureRoomExists(any());
        verify(liveKitService, never()).generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void joinMeetingRejectsMeetingWhoseEndTimeHasPassed() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusMinutes(1));
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () ->
            meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User")));

        assertEquals("Cuộc họp đã kết thúc hoặc đã bị hủy", exception.getMessage());
        verify(liveKitService, never()).ensureRoomExists(any());
        verify(liveKitService, never()).generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void publicGuestCanJoinAndReceivesPermissions() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(1L, "guest-123")).thenReturn(Optional.empty());
        when(meetingParticipantRepository.save(any(MeetingParticipantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(connectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.ensureRoomExists("meeting-abc")).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 1, 1L));
        when(liveKitService.generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn("jwt-token");

        var response = meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User"));

        assertEquals("jwt-token", response.token());
        assertEquals("guest:123", response.participant().attendeeId());
        assertEquals("GUEST", response.participant().role());
        assertNotNull(response.participant().chatWriteToken());
        assertFalse(response.participant().chatWriteToken().isBlank());
        assertEquals(false, response.permissions().canEndMeeting());
        assertEquals(false, response.permissions().canManageParticipants());

        ArgumentCaptor<MeetingParticipantConnectionEntity> connectionCaptor =
            ArgumentCaptor.forClass(MeetingParticipantConnectionEntity.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        assertEquals(
            MeetingChatTokenUtils.sha256(response.participant().chatWriteToken()),
            connectionCaptor.getValue().getChatTokenHash()
        );
        assertEquals(ParticipantConnectionStatus.PENDING, connectionCaptor.getValue().getStatus());
        assertNull(connectionCaptor.getValue().getJoinedAt());
        assertNull(connectionCaptor.getValue().getLeftAt());

        ArgumentCaptor<MeetingParticipantEntity> participantCaptor =
            ArgumentCaptor.forClass(MeetingParticipantEntity.class);
        verify(meetingParticipantRepository, org.mockito.Mockito.atLeastOnce()).save(participantCaptor.capture());
        MeetingParticipantEntity savedParticipant = participantCaptor.getAllValues().get(participantCaptor.getAllValues().size() - 1);
        assertEquals(ParticipantStatus.INVITED, savedParticipant.getStatus());
        assertNull(savedParticipant.getJoinedAt());
        assertNull(savedParticipant.getLeftAt());
    }

    @Test
    void joinMeetingDoesNotSetParticipantJoinedAtBeforeLiveKitWebhook() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey("guest-key");
        participant.setUserId("guest-123");
        participant.setFullName("Guest User");
        participant.setEmail("guest-123@guest.local");
        participant.setRole(MeetingRole.GUEST);
        participant.setStatus(ParticipantStatus.LEFT);
        participant.setJoinType(ParticipantJoinType.GUEST);
        LocalDateTime previousJoinedAt = LocalDateTime.now().minusHours(1);
        participant.setJoinedAt(previousJoinedAt);

        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(1L, "guest-123")).thenReturn(Optional.of(participant));
        when(meetingParticipantRepository.save(participant)).thenReturn(participant);
        when(connectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.ensureRoomExists("meeting-abc")).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 1, 1L));
        when(liveKitService.generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn("jwt-token");

        meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User"));

        assertEquals(ParticipantStatus.LEFT, participant.getStatus());
        assertEquals(previousJoinedAt, participant.getJoinedAt());
        verify(meetingParticipantRepository).save(participant);
    }

    @Test
    void joinMeetingLocksMeetingBeforeResolvingGuestParticipant() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(1L, "guest-123")).thenReturn(Optional.empty());
        when(meetingParticipantRepository.save(any(MeetingParticipantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(connectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.ensureRoomExists("meeting-abc")).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 1, 1L));
        when(liveKitService.generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn("jwt-token");

        meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User"));

        InOrder inOrder = inOrder(meetingRepository, meetingParticipantRepository, liveKitService);
        inOrder.verify(meetingRepository).findByIdForUpdate(1L);
        inOrder.verify(meetingParticipantRepository).findByMeetingIdAndUserId(1L, "guest-123");
        inOrder.verify(liveKitService).ensureRoomExists("meeting-abc");
        inOrder.verify(meetingRepository).findByIdForUpdate(1L);
        inOrder.verify(meetingParticipantRepository).findByMeetingIdAndUserId(1L, "guest-123");

        ArgumentCaptor<MeetingParticipantConnectionEntity> connectionCaptor =
            ArgumentCaptor.forClass(MeetingParticipantConnectionEntity.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        assertEquals(ParticipantConnectionStatus.PENDING, connectionCaptor.getValue().getStatus());
        assertNull(connectionCaptor.getValue().getJoinedAt());
    }

    @Test
    void joinMeetingRejectsRemovedParticipantAfterLockedMeetingLoad() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey("guest-key");
        participant.setUserId("guest-123");
        participant.setFullName("Guest User");
        participant.setEmail("guest-123@guest.local");
        participant.setRole(MeetingRole.GUEST);
        participant.setStatus(ParticipantStatus.REMOVED);
        participant.setJoinType(ParticipantJoinType.GUEST);

        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(1L, "guest-123")).thenReturn(Optional.of(participant));

        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.GUEST, "guest-123", "Guest User")));

        assertEquals("PARTICIPANT_REMOVED", exception.getMessage());
        verify(liveKitService, never()).ensureRoomExists(any());
        verify(liveKitService, never()).generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void meetingManagerJoinPermissionCanEndWithoutManagingParticipants() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        UserAccountEntity account = account(5L);
        MeetingParticipantEntity participant = authenticatedParticipant(meeting, account, MeetingRole.MEMBER);
        MeetingPrincipal principal = principal(5L, SystemRole.MEETING_MANAGER);

        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(userAccountRepository.findById(5L)).thenReturn(Optional.of(account));
        when(meetingParticipantRepository.findByMeetingIdAndUserAccountId(1L, 5L)).thenReturn(Optional.of(participant));
        when(meetingParticipantRepository.save(participant)).thenReturn(participant);
        when(connectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(liveKitService.ensureRoomExists("meeting-abc")).thenReturn(new LiveKitRoomResponse("meeting-abc", "RM_1", 1, 1L));
        when(liveKitService.generateJoinToken(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn("jwt-token");
        when(meetingAccessPolicyService.canEndMeeting(principal, meeting, participant)).thenReturn(true);
        when(meetingAccessPolicyService.canManageMeeting(principal, meeting, participant)).thenReturn(false);

        var response = meetingService.joinMeeting(1L, new JoinMeetingRequest(ParticipantJoinType.AUTHENTICATED, null, "Manager User"));

        assertEquals(true, response.permissions().canEndMeeting());
        assertEquals(false, response.permissions().canManageParticipants());
    }

    @Test
    void publicJoinInfoReturnsTitle() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.UPCOMING);
        meeting.setStartTime(LocalDateTime.now().minusMinutes(30));
        meeting.setEndTime(LocalDateTime.now().plusMinutes(30));
        when(meetingRepository.findById(1L)).thenReturn(Optional.of(meeting));

        var response = meetingService.getJoinInfo(1L);

        assertEquals(1L, response.id());
        assertEquals("Meeting", response.title());
        assertEquals(MeetingStatus.IN_PROGRESS, response.status());
        assertEquals(MeetingStatus.UPCOMING, meeting.getStatus());
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void privateJoinInfoHidesTitle() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PRIVATE);
        when(meetingRepository.findById(1L)).thenReturn(Optional.of(meeting));

        var response = meetingService.getJoinInfo(1L);

        assertEquals(null, response.title());
        assertEquals(MeetingAccessType.PRIVATE, response.accessType());
        assertEquals("Meeting", meeting.getTitle());
    }

    @Test
    void getMeetingRequiresViewAccessBeforeReturningDetail() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PRIVATE);
        when(meetingRepository.findById(1L)).thenReturn(Optional.of(meeting));

        var response = meetingService.getMeeting(1L);

        verify(meetingAccessPolicyService).requireCanViewMeeting(meeting);
        assertEquals(1L, response.id());
        assertEquals("Meeting", response.title());
    }

    @Test
    void getMeetingPropagatesViewAccessDenial() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PRIVATE);
        MeetingAccessDeniedException denial = new MeetingAccessDeniedException("FORBIDDEN");
        when(meetingRepository.findById(1L)).thenReturn(Optional.of(meeting));
        org.mockito.Mockito.doThrow(denial).when(meetingAccessPolicyService).requireCanViewMeeting(meeting);

        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> meetingService.getMeeting(1L));

        assertEquals(denial, exception);
        verify(meetingAccessPolicyService).requireCanViewMeeting(meeting);
    }

    @Test
    void endMeetingLocksAndFinalizesCaptureBeforeCompletingMeeting() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        meetingService.endMeeting(1L);

        InOrder inOrder = inOrder(meetingRepository, meetingAccessPolicyService, meetingCaptureService, meetingPollTerminalService, liveKitService);
        inOrder.verify(meetingRepository).findByIdForUpdate(1L);
        inOrder.verify(meetingAccessPolicyService).requireCanEndMeeting(meeting);
        inOrder.verify(meetingCaptureService).finalizeForTerminalTransition(1L);
        inOrder.verify(meetingPollTerminalService).closeForTerminalTransition(1L);
        inOrder.verify(meetingRepository).save(meeting);
        inOrder.verify(liveKitService).deleteRoom("meeting-abc");
        assertEquals(MeetingStatus.COMPLETED, meeting.getStatus());
    }

    @Test
    void endMeetingPropagatesEndPermissionDenialBeforeCaptureFinalization() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        MeetingAccessDeniedException denial = new MeetingAccessDeniedException("FORBIDDEN");
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        org.mockito.Mockito.doThrow(denial).when(meetingAccessPolicyService).requireCanEndMeeting(meeting);

        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> meetingService.endMeeting(1L));

        assertEquals(denial, exception);
        verify(meetingCaptureService, never()).finalizeForTerminalTransition(any());
        verify(meetingPollTerminalService, never()).closeForTerminalTransition(any());
        verify(meetingRepository, never()).save(any());
        verify(liveKitService, never()).deleteRoom(any());
    }

    @Test
    void endMeetingDoesNotCompleteMeetingWhenCaptureFinalizationFails() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        BusinessValidationException failure = new BusinessValidationException("Bản ghi âm chưa được hoàn tất");
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        org.mockito.Mockito.doThrow(failure).when(meetingCaptureService).finalizeForTerminalTransition(1L);

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> meetingService.endMeeting(1L));

        assertEquals(failure, exception);
        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
        verify(meetingPollTerminalService, never()).closeForTerminalTransition(any());
        verify(meetingRepository, never()).save(any());
        verify(liveKitService, never()).deleteRoom(any());
    }

    @Test
    void endMeetingAlreadyCompletedStillChecksCapture() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.COMPLETED);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        meetingService.endMeeting(1L);

        verify(meetingCaptureService).finalizeForTerminalTransition(1L);
        verify(meetingRepository, never()).save(any());
        verify(liveKitService).deleteRoom("meeting-abc");
    }

    @Test
    void endMeetingDoesNotConvertCancelledToCompleted() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.CANCELLED);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        meetingService.endMeeting(1L);

        assertEquals(MeetingStatus.CANCELLED, meeting.getStatus());
        verify(meetingCaptureService).finalizeForTerminalTransition(1L);
        verify(meetingRepository, never()).save(any());
        verify(liveKitService).deleteRoom("meeting-abc");
    }

    @Test
    void updateMeetingCannotTransitionToCompleted() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> meetingService.updateMeeting(1L, updateRequest(MeetingStatus.COMPLETED)));

        assertEquals("Su dung API ket thuc cuoc hop de chuyen sang COMPLETED", exception.getMessage());
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void updateMeetingWithNullStatusDoesNotPersistTimeDerivedCompleted() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));
        when(meetingRepository.save(meeting)).thenReturn(meeting);
        LocalDateTime pastStart = LocalDateTime.now().minusHours(2);
        LocalDateTime pastEnd = LocalDateTime.now().minusMinutes(1);

        meetingService.updateMeeting(1L, new vn.cusc.meetingservice.dto.request.UpdateMeetingRequest(
            "Updated",
            "Desc",
            pastStart,
            pastEnd,
            MeetingAccessType.PUBLIC,
            null
        ));

        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
    }

    @Test
    void updateMeetingCannotReopenCompletedMeeting() {
        MeetingEntity meeting = baseMeeting(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.COMPLETED);
        when(meetingRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> meetingService.updateMeeting(1L, updateRequest(MeetingStatus.IN_PROGRESS)));
 
        assertEquals("Khong the thay doi trang thai cuoc hop da ket thuc bang API cap nhat", exception.getMessage());
        verify(meetingRepository, never()).save(any());
    }

    private vn.cusc.meetingservice.dto.request.UpdateMeetingRequest updateRequest(MeetingStatus status) {
        return new vn.cusc.meetingservice.dto.request.UpdateMeetingRequest(
            "Updated",
            "Desc",
            LocalDateTime.now().plusMinutes(10),
            LocalDateTime.now().plusMinutes(40),
            MeetingAccessType.PUBLIC,
            status
        );
    }

    private MeetingEntity baseMeeting(MeetingAccessType accessType) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(1L);
        meeting.setMeetingCode("MTG-20260812-0001");
        meeting.setTitle("Meeting");
        meeting.setRoomName("meeting-abc");
        meeting.setRoomSid("RM_OLD");
        meeting.setStartTime(LocalDateTime.now().minusMinutes(30));
        meeting.setEndTime(LocalDateTime.now().plusMinutes(30));
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setAccessType(accessType);
        return meeting;
    }

    private MeetingParticipantEntity authenticatedParticipant(MeetingEntity meeting, UserAccountEntity account, MeetingRole role) {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey("participant-" + account.getId());
        participant.setUserAccount(account);
        participant.setUserId("account-" + account.getPublicId());
        participant.setInvitedEmailNormalized(account.getNormalizedEmail());
        participant.setFullName(account.getFullName());
        participant.setEmail(account.getNormalizedEmail());
        participant.setRole(role);
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        return participant;
    }

    private UserAccountEntity account(Long id) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
        account.setPublicId("public-" + id);
        account.setNormalizedEmail("user" + id + "@cusc.vn");
        account.setFullName("User " + id);
        account.setAccountStatus(vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE);
        return account;
    }

    private MeetingPrincipal principal(Long accountId, SystemRole role) {
        return new MeetingPrincipal(
            accountId,
            "public-" + accountId,
            "user-" + accountId,
            "user" + accountId + "@cusc.vn",
            "User " + accountId,
            null,
            vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE,
            role,
            true,
            vn.cusc.meetingservice.model.enums.AuthProvider.LOCAL,
            null
        );
    }
    private void authorizeCreator() {
        var account = new UserAccountEntity();
        account.setId(99L);
        account.setPublicId("creator");
        account.setFullName("Creator");
        account.setNormalizedEmail("creator@test.example");
        account.setAccountStatus(vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE);
        var principal = new MeetingPrincipal(99L, "creator", null, "creator@test.example", "Creator", null,
            vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE, SystemRole.USER, true,
            vn.cusc.meetingservice.model.enums.AuthProvider.LOCAL, null);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(userAccountRepository.findById(99L)).thenReturn(Optional.of(account));
    }

}
