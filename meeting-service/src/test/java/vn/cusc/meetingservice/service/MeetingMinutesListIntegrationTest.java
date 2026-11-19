package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.impl.MeetingMinutesQueryServiceImpl;

@DataJpaTest
@Import({MeetingMinutesAuthorizationService.class, MeetingMinutesQueryServiceImpl.class})
@ActiveProfiles("test")
class MeetingMinutesListIntegrationTest {

    @Autowired private MeetingMinutesQueryService queryService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingParticipantRepository participantRepository;
    @Autowired private UserAccountRepository accountRepository;
    @MockBean private CurrentUserService currentUserService;

    private UserAccountEntity chairman;
    private UserAccountEntity secretary;
    private UserAccountEntity member;

    @BeforeEach
    void setUp() {
        chairman = account("chairman@cusc.vn", SystemRole.USER);
        secretary = account("secretary@cusc.vn", SystemRole.USER);
        member = account("member@cusc.vn", SystemRole.USER);
        MeetingEntity first = meeting("MTG-LIST-1", "room-list-1");
        MeetingEntity second = meeting("MTG-LIST-2", "room-list-2");
        participant(first, chairman, MeetingRole.CHAIRMAN);
        participant(first, member, MeetingRole.MEMBER);
        participant(second, secretary, MeetingRole.SECRETARY);
    }

    @Test
    void privateMinutesRequireRosterForAdminAndEveryParticipantRole() {
        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(999L, SystemRole.ADMIN)));
        assertEquals(0, listSize());

        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(chairman.getId(), SystemRole.USER)));
        assertEquals(1, listSize());

        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(secretary.getId(), SystemRole.USER)));
        assertEquals(1, listSize());

        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(member.getId(), SystemRole.USER)));
        assertEquals(1, listSize());

        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.empty());
        assertThrows(MeetingAccessDeniedException.class, this::listSize);
    }

    @Test
    void explicitlyCompletedMeetingWithFutureEndTimeAppearsAndCancelledMeetingDoesNot() {
        MeetingEntity completedFuture = meeting("MTG-LIST-FUTURE", "room-list-future");
        completedFuture.setStartTime(LocalDateTime.now().minusHours(1));
        completedFuture.setEndTime(LocalDateTime.now().plusHours(1));
        completedFuture.setStatus(MeetingStatus.COMPLETED);
        meetingRepository.save(completedFuture);
        participant(completedFuture, chairman, MeetingRole.CHAIRMAN);

        MeetingEntity cancelled = meeting("MTG-LIST-CANCELLED", "room-list-cancelled");
        cancelled.setStatus(MeetingStatus.CANCELLED);
        meetingRepository.save(cancelled);
        participant(cancelled, chairman, MeetingRole.CHAIRMAN);

        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(chairman.getId(), SystemRole.USER)));

        var content = queryService.getMinutes(null, null, null, null, null, PageRequest.of(0, 20)).content();

        assertTrue(content.stream().anyMatch(item -> item.meetingId().equals(completedFuture.getId())));
        assertTrue(content.stream().noneMatch(item -> item.meetingId().equals(cancelled.getId())));
    }

    @Test
    void memberRoleFilterWorksForAnInvitedMember() {
        when(currentUserService.getCurrentPrincipal())
            .thenReturn(Optional.of(principal(member.getId(), SystemRole.USER)));
        var page = queryService.getMinutes(null, null, MeetingRole.MEMBER, null, null, PageRequest.of(0, 20));
        assertEquals(1, page.totalElements());
        assertEquals(MeetingRole.MEMBER, page.content().get(0).myRole());
    }

    private int listSize() {
        return queryService.getMinutes(null, null, null, null, null, PageRequest.of(0, 20))
            .content()
            .size();
    }

    private UserAccountEntity account(String email, SystemRole role) {
        UserAccountEntity account = new UserAccountEntity();
        account.setPublicId(UUID.randomUUID().toString());
        account.setNormalizedEmail(email);
        account.setFullName(email);
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setSystemRole(role);
        account.setEmailVerified(true);
        return accountRepository.save(account);
    }

    private MeetingEntity meeting(String code, String room) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setMeetingCode(code);
        meeting.setTitle(code);
        meeting.setRoomName(room);
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusHours(1));
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        return meetingRepository.save(meeting);
    }

    private void participant(MeetingEntity meeting, UserAccountEntity account, MeetingRole role) {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setUserAccount(account);
        participant.setUserId("account-" + account.getPublicId());
        participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setInvitedEmailNormalized(account.getNormalizedEmail());
        participant.setFullName(account.getFullName());
        participant.setEmail(account.getNormalizedEmail());
        participant.setRole(role);
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        participantRepository.save(participant);
    }

    private MeetingPrincipal principal(Long accountId, SystemRole role) {
        return new MeetingPrincipal(
            accountId,
            UUID.randomUUID().toString(),
            null,
            "principal@cusc.vn",
            "Principal",
            null,
            AccountStatus.ACTIVE,
            role,
            true,
            AuthProvider.LOCAL,
            null
        );
    }
}
