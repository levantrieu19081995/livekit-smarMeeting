package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;

@ExtendWith(MockitoExtension.class)
class MeetingAccessPolicyServiceTest {

    @Mock private CurrentUserService currentUserService;
    @Mock private MeetingParticipantRepository participantRepository;

    private MeetingAccessPolicyService policyService;
    private MeetingEntity meeting;

    @BeforeEach
    void setUp() {
        policyService = new MeetingAccessPolicyService(currentUserService, participantRepository);
        meeting = new MeetingEntity();
        meeting.setId(11L);
        meeting.setAccessType(MeetingAccessType.PUBLIC);
    }

    @Test
    void adminCannotManageAnotherCreatorsMeeting() {
        assertFalse(policyService.canManageMeeting(principal(1L, SystemRole.ADMIN), meeting, null));
    }

    @Test
    void creatorCanManageMeeting() {
        meeting.setCreatedByAccount(account(2L));

        assertTrue(policyService.canManageMeeting(principal(2L, SystemRole.USER), meeting, null));
    }

    @Test
    void chairmanCanManageMeeting() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        var chairman = participant(MeetingRole.CHAIRMAN);
        chairman.setUserAccount(account(3L));
        assertTrue(policyService.canManageMeeting(principal(3L, SystemRole.USER), meeting, chairman));
    }

    @Test
    void ordinaryParticipantCannotManageMeeting() {
        assertFalse(policyService.canManageMeeting(principal(4L, SystemRole.USER), meeting, participant(MeetingRole.MEMBER)));
    }

    @Test
    void secretaryCannotManageMeetingWhenNotCreator() {
        assertFalse(policyService.canManageMeeting(principal(7L, SystemRole.USER), meeting, participant(MeetingRole.SECRETARY)));
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        var secretary = participant(MeetingRole.SECRETARY);
        secretary.setUserAccount(account(7L));
        assertTrue(policyService.canManageMeeting(principal(7L, SystemRole.USER), meeting, secretary));
        secretary.setStatus(ParticipantStatus.REMOVED);
        assertFalse(policyService.canManageMeeting(principal(7L, SystemRole.USER), meeting, secretary));
    }

    @Test
    void guestCannotManageMeeting() {
        assertFalse(policyService.canManageMeeting(principal(8L, SystemRole.USER), meeting, participant(MeetingRole.GUEST)));
    }

    @Test
    void anonymousCannotManageMeetingAndRequireThrowsAuthenticationRequired() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.empty());

        assertFalse(policyService.canManageMeeting(null, meeting, participant(MeetingRole.CHAIRMAN)));
        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanManageMeeting(meeting));
        assertTrue(exception.getMessage().contains("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void meetingManagerWithoutCreatorOrChairmanCannotManageMeeting() {
        MeetingPrincipal principal = principal(5L, SystemRole.MEETING_MANAGER);
        MeetingParticipantEntity participant = participant(MeetingRole.MEMBER);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 5L)).thenReturn(Optional.of(participant));

        assertFalse(policyService.canManageMeeting(principal, meeting, participant));
        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanManageMeeting(meeting));
        assertTrue(exception.getMessage().contains("FORBIDDEN"));
    }

    @Test
    void publicMeetingCanOnlyBeEndedByCreator() {
        MeetingPrincipal meetingManager = principal(5L, SystemRole.MEETING_MANAGER);
        meeting.setCreatedByAccount(account(2L));

        assertFalse(policyService.canEndMeeting(principal(1L, SystemRole.ADMIN), meeting, null));
        assertFalse(policyService.canEndMeeting(meetingManager, meeting, participant(MeetingRole.MEMBER)));
        assertFalse(policyService.canManageMeeting(meetingManager, meeting, participant(MeetingRole.MEMBER)));
        assertTrue(policyService.canEndMeeting(principal(2L, SystemRole.USER), meeting, null));
        assertFalse(policyService.canEndMeeting(principal(3L, SystemRole.USER), meeting, participant(MeetingRole.CHAIRMAN)));
    }

    @Test
    void canEndMeetingDeniesSecretaryMemberGuestOrdinaryUserAndAnonymous() {
        assertFalse(policyService.canEndMeeting(principal(7L, SystemRole.USER), meeting, participant(MeetingRole.SECRETARY)));
        assertFalse(policyService.canEndMeeting(principal(8L, SystemRole.USER), meeting, participant(MeetingRole.MEMBER)));
        assertFalse(policyService.canEndMeeting(principal(9L, SystemRole.USER), meeting, participant(MeetingRole.GUEST)));
        assertFalse(policyService.canEndMeeting(principal(10L, SystemRole.USER), meeting, null));
        assertFalse(policyService.canEndMeeting(null, meeting, participant(MeetingRole.CHAIRMAN)));
    }

    @Test
    void requireCanEndMeetingUsesCurrentPrincipalAndAllowsCreator() {
        meeting.setCreatedByAccount(account(5L));
        MeetingPrincipal principal = principal(5L, SystemRole.MEETING_MANAGER);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 5L)).thenReturn(Optional.of(participant(MeetingRole.MEMBER)));

        assertDoesNotThrow(() -> policyService.requireCanEndMeeting(meeting));
    }

    @Test
    void requireCanEndMeetingRejectsUnauthorizedAndAnonymous() {
        MeetingPrincipal principal = principal(12L, SystemRole.USER);
        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal))
            .thenReturn(Optional.empty());
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 12L)).thenReturn(Optional.of(participant(MeetingRole.MEMBER)));

        MeetingAccessDeniedException forbidden = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanEndMeeting(meeting));
        assertTrue(forbidden.getMessage().contains("FORBIDDEN"));

        MeetingAccessDeniedException unauthenticated = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanEndMeeting(meeting));
        assertTrue(unauthenticated.getMessage().contains("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void requireAllowsChairman() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        var chairman = participant(MeetingRole.CHAIRMAN);
        chairman.setUserAccount(account(6L));
        MeetingPrincipal principal = principal(6L, SystemRole.USER);
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 6L)).thenReturn(Optional.of(chairman));

        assertDoesNotThrow(() -> policyService.requireCanManageMeeting(meeting));
    }

    @Test
    void publicMeetingAllowsEveryAuthenticatedSystemRole() {
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        meeting.setCreatedByAccount(account(9L));
        for (SystemRole role : SystemRole.values()) {
            assertTrue(policyService.canViewMeeting(principal(1L, role), meeting, null));
        }
        assertFalse(policyService.canViewMeeting(null, meeting, participant(MeetingRole.CHAIRMAN)));
    }

    @Test
    void requireCanViewMeetingAllowsSecretary() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        MeetingPrincipal principal = principal(10L, SystemRole.USER);
        MeetingParticipantEntity secretary = participant(MeetingRole.SECRETARY);
        secretary.setUserAccount(account(10L));
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 10L)).thenReturn(Optional.of(secretary));

        assertDoesNotThrow(() -> policyService.requireCanViewMeeting(meeting));
    }

    @Test
    void privateMeetingAllowsMemberAndRevokesRemovedMembership() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        MeetingPrincipal principal = principal(12L, SystemRole.USER);
        MeetingParticipantEntity member = participant(MeetingRole.MEMBER);
        member.setUserAccount(account(12L));
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(participantRepository.findByMeetingIdAndUserAccountId(11L, 12L)).thenReturn(Optional.of(member));

        assertDoesNotThrow(() -> policyService.requireCanViewMeeting(meeting));
        member.setStatus(ParticipantStatus.REMOVED);

        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanViewMeeting(meeting));
        assertTrue(exception.getMessage().contains("FORBIDDEN"));
    }

    @Test
    void privateMeetingAllowsCreatorButDeniesUninvitedSystemRoles() {
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        meeting.setCreatedByAccount(account(1L));
        for (SystemRole role : SystemRole.values()) {
            assertTrue(policyService.canViewMeeting(principal(1L, role), meeting, null));
            assertFalse(policyService.canViewMeeting(principal(3L, role), meeting, null));
        }
        MeetingParticipantEntity member = participant(MeetingRole.MEMBER);
        member.setUserAccount(account(2L));
        assertFalse(policyService.canViewMeeting(principal(3L, SystemRole.USER), meeting, member));
    }

    @Test
    void requireCanViewMeetingRejectsAnonymous() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.empty());

        MeetingAccessDeniedException exception = assertThrows(MeetingAccessDeniedException.class,
            () -> policyService.requireCanViewMeeting(meeting));
        assertTrue(exception.getMessage().contains("AUTHENTICATION_REQUIRED"));
    }

    private MeetingParticipantEntity participant(MeetingRole role) {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setRole(role);
        participant.setStatus(ParticipantStatus.INVITED);
        return participant;
    }

    private UserAccountEntity account(Long id) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
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
            AccountStatus.ACTIVE,
            role,
            true,
            AuthProvider.LOCAL,
            null
        );
    }
}
