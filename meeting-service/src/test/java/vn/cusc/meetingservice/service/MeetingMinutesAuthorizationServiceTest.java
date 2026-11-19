package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesAuthorizationServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private UserAccountRepository accountRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private MeetingPrincipal principal;

    private MeetingMinutesAuthorizationService service;
    private MeetingEntity meeting;
    private MeetingParticipantEntity participant;

    @BeforeEach
    void setUp() {
        service = new MeetingMinutesAuthorizationService(
            meetingRepository,
            participantRepository,
            accountRepository,
            currentUserService
        );
        meeting = new MeetingEntity();
        meeting.setId(10L);
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setStatus(ParticipantStatus.INVITED);
        var account = new UserAccountEntity();
        account.setId(99L);
        participant.setUserAccount(account);
    }

    @Test
    void adminCannotManageWithoutOwnershipOrPrivateOfficerRole() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(principal.isEnabled()).thenReturn(true);
        when(principal.accountId()).thenReturn(99L);
        assertThrows(MeetingAccessDeniedException.class, () -> service.requireMeetingAccess(meeting));
        meeting.setCreatedByAccount(participant.getUserAccount());
        assertDoesNotThrow(() -> service.requireMeetingAccess(meeting));
    }

    @Test
    void chairmanAndSecretaryCanManageButMemberCannot() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(principal.isEnabled()).thenReturn(true);
        when(principal.accountId()).thenReturn(99L);
        when(participantRepository.findByMeetingIdAndUserAccountId(10L, 99L))
            .thenReturn(Optional.of(participant));

        participant.setRole(MeetingRole.CHAIRMAN);
        assertDoesNotThrow(() -> service.requireMeetingAccess(meeting));
        participant.setRole(MeetingRole.SECRETARY);
        assertDoesNotThrow(() -> service.requireMeetingAccess(meeting));
        participant.setRole(MeetingRole.MEMBER);
        assertThrows(MeetingAccessDeniedException.class, () -> service.requireMeetingAccess(meeting));
        participant.setRole(MeetingRole.SECRETARY);
        participant.setStatus(ParticipantStatus.REMOVED);
        assertThrows(MeetingAccessDeniedException.class, () -> service.requireMeetingAccess(meeting));
    }

    @Test
    void guestIsDeniedAndServerDerivesEditorSource() {
        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(principal));
        assertThrows(MeetingAccessDeniedException.class, service::requirePrincipal);

        when(principal.accountId()).thenReturn(99L);
        when(principal.isEnabled()).thenReturn(true);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));
        participant.setRole(MeetingRole.SECRETARY);
        when(participantRepository.findByMeetingIdAndUserAccountId(10L, 99L))
            .thenReturn(Optional.of(participant));

        assertEquals(MinutesVersionSource.SECRETARY_EDITED, service.resolveVersionSource(10L));
        meeting.setCreatedByAccount(participant.getUserAccount());
        assertEquals(MinutesVersionSource.OWNER_EDITED, service.resolveVersionSource(10L));
    }

    @Test
    void publicMinutesAreReadableWithoutRosterButMemberCannotEdit() {
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(principal.accountId()).thenReturn(99L);
        when(principal.isEnabled()).thenReturn(true);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));
        assertDoesNotThrow(service::requireListAccess);
        assertEquals(meeting, service.requireMeetingView(10L));
        assertThrows(MeetingAccessDeniedException.class, () -> service.requireMeetingAccess(meeting));
    }

    @Test
    void privateMinutesFollowMembershipForEveryRoleAndRejectRemovedMembership() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(principal.accountId()).thenReturn(99L);
        when(principal.isEnabled()).thenReturn(true);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));
        var account = new UserAccountEntity();
        account.setId(99L);
        participant.setUserAccount(account);
        participant.setStatus(ParticipantStatus.INVITED);
        when(participantRepository.findByMeetingIdAndUserAccountId(10L, 99L)).thenReturn(Optional.of(participant));
        for (MeetingRole role : MeetingRole.values()) {
            participant.setRole(role);
            assertEquals(meeting, service.requireMeetingView(10L));
        }
        participant.setStatus(ParticipantStatus.REMOVED);
        assertThrows(MeetingAccessDeniedException.class, () -> service.requireMeetingView(10L));
    }
}
