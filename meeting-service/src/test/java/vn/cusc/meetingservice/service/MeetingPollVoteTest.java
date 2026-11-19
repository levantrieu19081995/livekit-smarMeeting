package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import vn.cusc.meetingservice.dto.poll.MeetingPollRequest;
import vn.cusc.meetingservice.dto.poll.MeetingPollResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollVoteRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingPollConflictException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingPollBallotRepository;
import vn.cusc.meetingservice.repository.MeetingPollRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@DataJpaTest
@ActiveProfiles("test")
@Import({MeetingPollService.class, MeetingPollAuthorizationService.class, MeetingStatusResolver.class})
class MeetingPollVoteTest {
    @Autowired private MeetingPollService service;
    @Autowired private MeetingPollRepository polls;
    @Autowired private MeetingPollBallotRepository ballots;
    @Autowired private EntityManager em;
    @MockBean private CurrentUserService currentUser;
    private MeetingPrincipal principal;
    private MeetingEntity meeting;
    private UserAccountEntity manager;
    private MeetingParticipantEntity member;
    private MeetingParticipantEntity second;

    @BeforeEach
    void setUp() {
        manager = account(SystemRole.MEETING_MANAGER);
        meeting = new MeetingEntity();
        String key = UUID.randomUUID().toString().replace("-", "");
        meeting.setMeetingCode(key);
        meeting.setRoomName(key);
        meeting.setTitle("Vote test");
        meeting.setStartTime(LocalDateTime.now().minusHours(1));
        meeting.setEndTime(LocalDateTime.now().plusHours(1));
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        meeting.setCreatedByAccount(manager);
        em.persist(meeting);
        member = participant(MeetingRole.MEMBER);
        second = participant(MeetingRole.MEMBER);
        var chairman = participant(MeetingRole.CHAIRMAN);
        chairman.setUserAccount(manager);
        login(manager);
        when(currentUser.getCurrentPrincipal()).thenAnswer(invocation -> Optional.ofNullable(principal));
    }

    @Test
    void retryAndReconnectRestoreSameBallotAndOriginalTimestamps() {
        var poll = open(false, MeetingPollResultVisibility.AFTER_CLOSE, false);
        long option = poll.options().get(0).id();
        login(member.getUserAccount());
        var first = service.vote(meeting.getId(), poll.id(), vote(option));
        assertNotNull(first.myVote());
        assertFalse(first.canVote());
        em.clear();
        var replay = service.vote(meeting.getId(), poll.id(), vote(option));
        assertEquals(first.myVote(), replay.myVote());
        assertEquals(1, replay.votedCount());
        assertEquals(1, ballots.countByPollId(poll.id()));
        assertEquals(first.myVote(), service.get(meeting.getId(), poll.id()).myVote());
    }

    @Test
    void identicalReplayAfterCloseSucceedsButNewOrChangedVoteCannotEnterClosedPoll() {
        var poll = open(false, MeetingPollResultVisibility.AFTER_CLOSE, false);
        login(member.getUserAccount());
        var first = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id()));
        login(manager);
        service.close(meeting.getId(), poll.id());
        login(member.getUserAccount());
        assertEquals(first.myVote(), service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id())).myVote());
        assertThrows(MeetingPollConflictException.class, () -> service.vote(meeting.getId(), poll.id(), vote(poll.options().get(1).id())));
        login(second.getUserAccount());
        assertThrows(MeetingPollConflictException.class, () -> service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id())));
    }

    @Test
    void changeVotePolicyPreservesOneBallotAndInitialSubmission() {
        var poll = open(true, MeetingPollResultVisibility.LIVE, false);
        login(member.getUserAccount());
        var first = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id()));
        var changed = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(1).id()));
        assertEquals(first.myVote().submittedAt(), changed.myVote().submittedAt());
        assertEquals(List.of(poll.options().get(1).id()), changed.myVote().optionIds());
        assertEquals(1, ballots.countByPollId(poll.id()));
        assertEquals(0, changed.results().get(0).count());
        assertEquals(1, changed.results().get(1).count());
        assertEquals(100.0, changed.results().get(1).percentage());
    }

    @Test
    void deniesChangingLockedVoteAndLeavesSavedSelectionIntact() {
        var poll = open(false, MeetingPollResultVisibility.LIVE, false);
        login(member.getUserAccount());
        var first = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id()));
        assertThrows(MeetingPollConflictException.class, () -> service.vote(meeting.getId(), poll.id(), vote(poll.options().get(1).id())));
        assertEquals(first.myVote(), service.get(meeting.getId(), poll.id()).myVote());
    }

    @ParameterizedTest
    @EnumSource(MeetingPollResultVisibility.class)
    void resultVisibilityIsEnforcedForMemberAndManager(MeetingPollResultVisibility visibility) {
        var poll = open(false, visibility, false);
        login(member.getUserAccount());
        var voted = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id()));
        assertEquals(visibility != MeetingPollResultVisibility.AFTER_CLOSE, voted.canSeeResults());
        if (!voted.canSeeResults()) assertNull(voted.results());
        login(manager);
        var managed = service.get(meeting.getId(), poll.id());
        assertEquals(visibility != MeetingPollResultVisibility.AFTER_CLOSE, managed.canSeeResults());
        if (visibility == MeetingPollResultVisibility.AFTER_CLOSE) assertNull(managed.results());
        service.close(meeting.getId(), poll.id());
        login(member.getUserAccount());
        var closed = service.get(meeting.getId(), poll.id());
        assertTrue(closed.canSeeResults());
        assertEquals(3, closed.eligibleCount());
        assertEquals(1, closed.votedCount());
        if (closed.canSeeResults()) assertEquals(100.0, closed.results().get(0).percentage());
        else assertNull(closed.results());
    }

    @Test
    void multiChoiceReplayIsOrderIndependentAndPercentagesUseRespondents() {
        var poll = open(false, MeetingPollResultVisibility.LIVE, true);
        login(member.getUserAccount());
        long a = poll.options().get(0).id();
        long b = poll.options().get(1).id();
        var first = service.vote(meeting.getId(), poll.id(), vote(a, b));
        assertEquals(first.myVote(), service.vote(meeting.getId(), poll.id(), vote(b, a)).myVote());
        login(second.getUserAccount());
        var result = service.vote(meeting.getId(), poll.id(), vote(a));
        assertEquals(2, result.votedCount());
        assertEquals(100.0, result.results().get(0).percentage());
        assertEquals(50.0, result.results().get(1).percentage());
        assertEquals(150.0, result.results().stream().mapToDouble(r -> r.percentage()).sum());
    }

    @Test
    void rejectsForeignDuplicateAndExcessOptionsWithoutCreatingBallot() {
        var poll = open(false, MeetingPollResultVisibility.LIVE, false);
        login(manager);
        var foreign = service.create(meeting.getId(), request(false, MeetingPollResultVisibility.LIVE, false));
        login(member.getUserAccount());
        long option = poll.options().get(0).id();
        assertThrows(BusinessValidationException.class, () -> service.vote(meeting.getId(), poll.id(), vote(foreign.options().get(0).id())));
        assertThrows(BusinessValidationException.class, () -> service.vote(meeting.getId(), poll.id(), vote(option, option)));
        assertThrows(BusinessValidationException.class, () -> service.vote(meeting.getId(), poll.id(), vote(option, poll.options().get(1).id())));
        assertEquals(0, ballots.countByPollId(poll.id()));
    }

    @Test
    void guestAndOwnerMayVoteButRemovedMemberCannot() {
        var guest = participant(MeetingRole.GUEST);
        var poll = open(false, MeetingPollResultVisibility.LIVE, false);
        long option = poll.options().get(0).id();
        login(guest.getUserAccount());
        assertTrue(service.get(meeting.getId(), poll.id()).canVote());
        assertEquals(1, service.vote(meeting.getId(), poll.id(), vote(option)).votedCount());
        login(manager);
        assertEquals(2, service.vote(meeting.getId(), poll.id(), vote(option)).votedCount());
        member.setStatus(ParticipantStatus.REMOVED);
        login(member.getUserAccount());
        assertThrows(MeetingAccessDeniedException.class, () -> service.vote(meeting.getId(), poll.id(), vote(option)));
        assertEquals(2, ballots.countByPollId(poll.id()));
    }

    @Test
    void lateParticipantMayVoteButExpiredMeetingRejectsNewVote() {
        var poll = open(false, MeetingPollResultVisibility.LIVE, false);
        var late = participant(MeetingRole.MEMBER);
        login(late.getUserAccount());
        assertEquals(3, service.get(meeting.getId(), poll.id()).eligibleCount());
        var voted = service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id()));
        assertEquals(4, voted.eligibleCount());
        assertEquals(1, voted.votedCount());
        assertNotNull(voted.myVote());
        meeting.setEndTime(LocalDateTime.now().minusSeconds(1));
        login(member.getUserAccount());
        assertFalse(service.get(meeting.getId(), poll.id()).canVote());
        assertThrows(MeetingPollConflictException.class, () -> service.vote(meeting.getId(), poll.id(), vote(poll.options().get(0).id())));
        assertEquals(1, ballots.countByPollId(poll.id()));
    }

    private MeetingPollResponse open(boolean change, MeetingPollResultVisibility visibility, boolean multiple) {
        login(manager);
        var poll = service.create(meeting.getId(), request(change, visibility, multiple));
        return service.open(meeting.getId(), poll.id());
    }

    private MeetingPollRequest request(boolean change, MeetingPollResultVisibility visibility, boolean multiple) {
        return new MeetingPollRequest("Approve?", null,
            multiple ? MeetingPollType.MULTIPLE_CHOICE : MeetingPollType.YES_NO_ABSTAIN,
            change, visibility, 1, multiple ? 2 : 1,
            List.of(new MeetingPollRequest.OptionInput("A", null), new MeetingPollRequest.OptionInput("B", null)),
            List.of(member.getParticipantKey(), second.getParticipantKey()));
    }

    private MeetingPollVoteRequest vote(Long... ids) { return new MeetingPollVoteRequest(List.of(ids)); }

    private UserAccountEntity account(SystemRole role) {
        var value = new UserAccountEntity();
        value.setPublicId(UUID.randomUUID().toString());
        value.setNormalizedEmail(value.getPublicId() + "@example.test");
        value.setFullName("Voter");
        value.setAccountStatus(AccountStatus.ACTIVE);
        value.setSystemRole(role);
        em.persist(value);
        return value;
    }

    private MeetingParticipantEntity participant(MeetingRole role) {
        var user = account(SystemRole.USER);
        var value = new MeetingParticipantEntity();
        value.setMeeting(meeting);
        value.setUserAccount(user);
        value.setParticipantKey(UUID.randomUUID().toString());
        value.setUserId(user.getPublicId());
        value.setFullName(role.name());
        value.setEmail(user.getNormalizedEmail());
        value.setRole(role);
        value.setStatus(ParticipantStatus.INVITED);
        value.setJoinType(ParticipantJoinType.AUTHENTICATED);
        em.persist(value);
        return value;
    }

    private void login(UserAccountEntity user) {
        principal = new MeetingPrincipal(user.getId(), user.getPublicId(), null, user.getNormalizedEmail(),
            user.getFullName(), null, AccountStatus.ACTIVE, user.getSystemRole(), true, AuthProvider.LOCAL, null);
    }
}
