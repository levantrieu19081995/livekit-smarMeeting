package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.config.FrontendProperties;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.request.*;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.*;
import vn.cusc.meetingservice.mapper.*;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.security.*;
import vn.cusc.meetingservice.service.impl.MeetingServiceImpl;
import vn.cusc.meetingservice.util.*;

@ExtendWith(MockitoExtension.class)
class MeetingCreationFlowTest {
    @Mock MeetingRepository meetings;
    @Mock MeetingParticipantRepository participants;
    @Mock MeetingParticipantConnectionRepository connections;
    @Mock UserAccountRepository accounts;
    @Mock UserAccountService accountService;
    @Mock CurrentUserService current;
    @Mock LiveKitService liveKit;
    @Mock MeetingCaptureService capture;
    @Mock MeetingPollTerminalService pollTerminal;
    MeetingService service;
    MeetingEntity saved;
    UserAccountEntity owner;
    MeetingPrincipal principal;
    MeetingAccessPolicyService policy;

    @BeforeEach void setup() {
        owner = new UserAccountEntity(); owner.setId(17L); owner.setPublicId("owner");
        owner.setFullName("Meeting owner"); owner.setNormalizedEmail("owner@example.test");
        owner.setAccountStatus(AccountStatus.ACTIVE);
        principal = principal(17L, SystemRole.USER);
        lenient().when(current.getCurrentPrincipal()).thenAnswer(i -> Optional.ofNullable(principal));
        lenient().when(accounts.findById(17L)).thenReturn(Optional.of(owner));
        lenient().when(liveKit.createRoom(anyString())).thenReturn(new LiveKitRoomResponse("room", "RM_test", 0, 0));
        lenient().when(liveKit.ensureRoomExists(anyString())).thenReturn(new LiveKitRoomResponse("room", "RM_test", 0, 0));
        lenient().when(liveKit.generateJoinToken(anyString(), anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean())).thenReturn("test-token");
        lenient().when(meetings.save(any())).thenAnswer(i -> { saved = i.getArgument(0); saved.setId(7L); return saved; });
        lenient().when(meetings.findByIdForUpdate(7L)).thenAnswer(i -> Optional.ofNullable(saved));
        lenient().when(participants.save(any())).thenAnswer(i -> i.getArgument(0));
        var frontend = new FrontendProperties(); frontend.setBaseUrl("http://localhost:3000");
        var properties = new LiveKitProperties(); properties.setUrl("ws://localhost:7880");
        policy = new MeetingAccessPolicyService(current, participants);
        service = new MeetingServiceImpl(meetings, participants, connections, accounts, accountService, current,
            new MeetingCodeGenerator(), new RoomNameGenerator(), liveKit, new MeetingMapper(frontend, new MeetingParticipantMapper()),
            new MeetingStatusResolver(), properties, policy, capture, pollTerminal);
    }

    @Test void instantUsesServerTimeAndHasNoScheduledEnd() {
        var before = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var response = service.createMeeting(new CreateMeetingRequest(null, "Description", before.plusYears(1),
            before.plusYears(2), MeetingAccessType.PUBLIC, List.of(), MeetingCreationMode.INSTANT));
        assertEquals(MeetingCreationMode.INSTANT, response.creationMode());
        assertEquals(MeetingStatus.IN_PROGRESS, response.status());
        assertFalse(response.startTime().isBefore(before)); assertFalse(response.startTime().isAfter(LocalDateTime.now()));
        assertEquals(0, response.startTime().getNano() % 1000);
        assertNull(response.endTime()); assertTrue(response.canManage()); assertEquals(owner, saved.getCreatedByAccount());
        assertEquals("Description", saved.getDescription()); assertFalse(saved.getTitle().isBlank());
    }

    @Test void laterHasNoScheduleUntilItsFirstJoinAndEndsExplicitly() {
        var response = service.createMeeting(quick(MeetingCreationMode.LATER));
        assertNull(response.startTime()); assertNull(response.endTime()); assertEquals(MeetingStatus.UPCOMING, response.status());
        service.joinMeeting(7L, new JoinMeetingRequest(ParticipantJoinType.AUTHENTICATED, null, "Owner"));
        assertNotNull(saved.getStartTime()); assertNull(saved.getEndTime());
        assertEquals(MeetingStatus.IN_PROGRESS, saved.getStatus());
        service.endMeeting(7L);
        assertEquals(MeetingStatus.COMPLETED, saved.getStatus()); assertNotNull(saved.getEndTime());
        assertFalse(saved.getEndTime().isBefore(saved.getStartTime()));
        verify(capture).finalizeForTerminalTransition(7L); verify(pollTerminal).closeForTerminalTransition(7L);
    }

    @Test void ownerEditsDescriptionWithoutTurningQuickMeetingIntoASchedule() {
        service.createMeeting(quick(MeetingCreationMode.INSTANT));
        var actualStart = saved.getStartTime();
        var result = service.updateMeeting(7L, new UpdateMeetingRequest("Updated", "New description", null, null, MeetingAccessType.PUBLIC, null));
        assertEquals(actualStart, result.startTime()); assertNull(result.endTime()); assertEquals("Updated", result.title());
        assertTrue(result.canManage());
    }

    @ParameterizedTest @EnumSource(SystemRole.class)
    void aDifferentAccountCannotEditDeleteOrEndEvenWithAnAdminRole(SystemRole role) {
        service.createMeeting(quick(MeetingCreationMode.INSTANT)); principal = principal(99L, role);
        assertThrows(MeetingAccessDeniedException.class, () -> service.updateMeeting(7L,
            new UpdateMeetingRequest("Changed", null, null, null, MeetingAccessType.PUBLIC, null)));
        assertThrows(MeetingAccessDeniedException.class, () -> service.deleteMeeting(7L));
        assertThrows(MeetingAccessDeniedException.class, () -> service.endMeeting(7L));
        assertNotEquals("Changed", saved.getTitle()); verify(meetings, never()).delete(any(MeetingEntity.class));
        verifyNoInteractions(capture, pollTerminal);
    }

    @Test void scheduledRequestsKeepRequiredDatesAndPrivateQuickRequestsAreRejectedBeforeRoomCreation() {
        assertThrows(BusinessValidationException.class, () -> service.createMeeting(
            new CreateMeetingRequest("Private", null, null, null, MeetingAccessType.PRIVATE, List.of(), MeetingCreationMode.SCHEDULED)));
        assertThrows(BusinessValidationException.class, () -> service.createMeeting(
            new CreateMeetingRequest("Private", null, null, null, MeetingAccessType.PRIVATE, List.of(), MeetingCreationMode.INSTANT)));
        verifyNoInteractions(liveKit);
    }

    @ParameterizedTest @EnumSource(MeetingRole.class)
    void privateOfficersMayManageButPublicOfficersDoNotOverrideTheCreator(MeetingRole role) {
        var meeting = new MeetingEntity(); meeting.setId(7L); meeting.setCreatedByAccount(owner);
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        var user = new UserAccountEntity(); user.setId(99L);
        var participant = new MeetingParticipantEntity(); participant.setUserAccount(user);
        participant.setMeeting(meeting); participant.setRole(role); participant.setStatus(ParticipantStatus.INVITED);
        var other = principal(99L, SystemRole.USER);
        assertEquals(role == MeetingRole.CHAIRMAN || role == MeetingRole.SECRETARY, policy.canManageMeeting(other, meeting, participant));
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        assertFalse(policy.canManageMeeting(other, meeting, participant));
        participant.setStatus(ParticipantStatus.REMOVED); meeting.setAccessType(MeetingAccessType.PRIVATE);
        assertFalse(policy.canManageMeeting(other, meeting, participant));
    }

    @Test void anonymousCreationCannotAllocateAnUnownedMeeting() {
        principal = null;
        assertThrows(MeetingAccessDeniedException.class, () -> service.createMeeting(quick(MeetingCreationMode.INSTANT)));
        verifyNoInteractions(liveKit);
    }

    private CreateMeetingRequest quick(MeetingCreationMode mode) {
        return new CreateMeetingRequest("Quick meeting", null, null, null, MeetingAccessType.PUBLIC, List.of(), mode);
    }
    private MeetingPrincipal principal(Long id, SystemRole role) {
        return new MeetingPrincipal(id, "user-" + id, "user-" + id, "user" + id + "@example.test", "User " + id, null,
            AccountStatus.ACTIVE, role, true, AuthProvider.LOCAL, null);
    }
}
