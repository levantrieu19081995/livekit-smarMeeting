package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingPollConflictException;
import vn.cusc.meetingservice.exception.MeetingPollNotFoundException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingPollEligibleVoterRepository;
import vn.cusc.meetingservice.repository.MeetingPollRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@DataJpaTest
@ActiveProfiles("test")
@Import({MeetingPollService.class, MeetingPollAuthorizationService.class, MeetingStatusResolver.class})
class MeetingPollLifecycleTest {
    @Autowired private MeetingPollService service;
    @Autowired private MeetingPollAuthorizationService authorization;
    @Autowired private MeetingPollRepository polls;
    @Autowired private MeetingPollEligibleVoterRepository roster;
    @Autowired private EntityManager em;
    @MockBean private CurrentUserService currentUser;
    private MeetingPrincipal principal;
    private MeetingEntity meeting;
    private UserAccountEntity creator;
    private MeetingParticipantEntity member;
    private MeetingParticipantEntity secretary;

    @BeforeEach
    void setUp() {
        creator = account(SystemRole.MEETING_MANAGER);
        meeting = new MeetingEntity();
        String key = UUID.randomUUID().toString().replace("-", "");
        meeting.setMeetingCode(key);
        meeting.setRoomName(key);
        meeting.setTitle("Lifecycle test");
        meeting.setStartTime(LocalDateTime.now().minusHours(1));
        meeting.setEndTime(LocalDateTime.now().plusHours(1));
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        meeting.setCreatedByAccount(creator);
        em.persist(meeting);
        member = participant(MeetingRole.MEMBER);
        secretary = participant(MeetingRole.SECRETARY);
        var chairman = participant(MeetingRole.CHAIRMAN);
        chairman.setUserAccount(creator);
        login(creator);
        when(currentUser.getCurrentPrincipal()).thenAnswer(invocation -> Optional.ofNullable(principal));
    }

    @Test
    void secretaryCanCreateAndEditDraftWithCanonicalChoicesAndExplicitVoters() {
        login(secretary.getUserAccount());
        var created = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        assertEquals(MeetingPollStatus.DRAFT, created.status());
        assertEquals(List.of(MeetingPollOptionCode.YES, MeetingPollOptionCode.NO, MeetingPollOptionCode.ABSTAIN),
            created.options().stream().map(value -> value.code()).toList());
        assertEquals(secretary.getUserAccount().getId(), polls.findById(created.id()).orElseThrow().getCreatedByAccount().getId());
        assertNull(roster.findByPollIdAndParticipantId(created.id(), member.getId()).orElseThrow().getSnapshotAt());
        var updated = service.update(meeting.getId(), created.id(), request(MeetingPollType.SINGLE_CHOICE, secretary.getParticipantKey()));
        assertTrue(updated.eligibleParticipantKeys().containsAll(List.of(secretary.getParticipantKey(), member.getParticipantKey())));
        assertEquals(2, updated.options().size());
        assertEquals(3, roster.findByPollIdOrderByIdAsc(created.id()).size());
    }

    @ParameterizedTest
    @EnumSource(MeetingRole.class)
    void everyParticipantRoleCanCreateAndOpen(MeetingRole role) {
        MeetingParticipantEntity candidate = participant(role);
        login(candidate.getUserAccount());
        assertTrue(service.context(meeting.getId()).canManage());
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN));
        assertEquals(MeetingPollStatus.OPEN, service.open(meeting.getId(), draft.id()).status());
    }

    @Test
    void systemManagerCanReadButCannotManageMeetingCreatedBySomeoneElse() {
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        login(account(SystemRole.MEETING_MANAGER));
        assertFalse(service.context(meeting.getId()).canManage());
        assertThrows(MeetingAccessDeniedException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey())));
        login(account(SystemRole.ADMIN));
        assertFalse(service.context(meeting.getId()).canManage());
    }

    @Test
    void deniesAnonymousOutsiderAndRemovedSecretary() {
        principal = null;
        assertThrows(MeetingAccessDeniedException.class, () -> service.context(meeting.getId()));
        login(account(SystemRole.USER));
        assertThrows(MeetingAccessDeniedException.class, () -> service.context(meeting.getId()));
        secretary.setStatus(ParticipantStatus.REMOVED);
        login(secretary.getUserAccount());
        assertThrows(MeetingAccessDeniedException.class, () -> service.context(meeting.getId()));
    }

    @Test
    void freezesSnapshotAndPreventsEditingOrReopeningAfterOpen() {
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        var open = service.open(meeting.getId(), draft.id());
        assertEquals(MeetingPollStatus.OPEN, open.status());
        var snapshot = roster.findByPollIdAndParticipantId(draft.id(), member.getId()).orElseThrow();
        String name = snapshot.getFullNameSnapshot();
        LocalDateTime timestamp = snapshot.getSnapshotAt();
        member.setFullName("Updated");
        member.setRole(MeetingRole.GUEST);
        service.open(meeting.getId(), draft.id());
        assertEquals(name, snapshot.getFullNameSnapshot());
        assertEquals(timestamp, snapshot.getSnapshotAt());
        assertThrows(MeetingPollConflictException.class, () -> service.update(meeting.getId(), draft.id(), request(MeetingPollType.SINGLE_CHOICE, secretary.getParticipantKey())));
        assertEquals(MeetingPollStatus.CLOSED, service.close(meeting.getId(), draft.id()).status());
        assertEquals(MeetingPollStatus.CLOSED, service.close(meeting.getId(), draft.id()).status());
        assertThrows(MeetingPollConflictException.class, () -> service.open(meeting.getId(), draft.id()));
    }

    @Test
    void skipsRemovedParticipantsAndStillRejectsSecondOpen() {
        var one = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        var two = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, secretary.getParticipantKey()));
        secretary.setStatus(ParticipantStatus.REMOVED);
        service.open(meeting.getId(), one.id());
        assertTrue(roster.findByPollIdAndParticipantId(one.id(), secretary.getId()).isEmpty());
        assertThrows(MeetingPollConflictException.class, () -> service.open(meeting.getId(), two.id()));
        assertNull(roster.findByPollIdOrderByIdAsc(two.id()).get(0).getSnapshotAt());
        service.close(meeting.getId(), one.id());
        secretary.setStatus(ParticipantStatus.INVITED);
        assertEquals(MeetingPollStatus.OPEN, service.open(meeting.getId(), two.id()).status());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalMeetingsAreReadOnly(MeetingStatus status) {
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        meeting.setStatus(status);
        assertEquals(draft.id(), service.get(meeting.getId(), draft.id()).id());
        assertThrows(MeetingPollConflictException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey())));
        assertThrows(MeetingPollConflictException.class, () -> service.update(meeting.getId(), draft.id(), request(MeetingPollType.SINGLE_CHOICE, member.getParticipantKey())));
        assertThrows(MeetingPollConflictException.class, () -> service.open(meeting.getId(), draft.id()));
    }

    @Test
    void upcomingDraftAllowedButOpeningAndTimeDerivedCompletedWritesAreRejected() {
        meeting.setStartTime(LocalDateTime.now().plusHours(1));
        meeting.setEndTime(LocalDateTime.now().plusHours(2));
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        assertThrows(MeetingPollConflictException.class, () -> service.open(meeting.getId(), draft.id()));
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusHours(1));
        assertThrows(MeetingPollConflictException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey())));
        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
    }

    @Test
    void participantsManageDraftsButOutsideViewersCannotManage() {
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN));
        login(member.getUserAccount());
        assertEquals(1, service.list(meeting.getId(), 0, 20).totalElements());
        assertTrue(service.get(meeting.getId(), draft.id()).canManage());
        assertFalse(service.context(meeting.getId()).candidates().isEmpty());
        service.open(meeting.getId(), draft.id());
        assertThrows(MeetingPollNotFoundException.class, () -> service.get(meeting.getId(), Long.MAX_VALUE));
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        login(account(SystemRole.USER));
        assertTrue(service.get(meeting.getId(), draft.id()).eligibleParticipantKeys().isEmpty());
        assertFalse(service.context(meeting.getId()).canManage());
    }

    @Test
    void includesGuestsButRejectsRemovedForeignOrDuplicateKeys() {
        var guest = participant(MeetingRole.GUEST);
        assertTrue(authorization.canBeEligible(guest));
        assertTrue(service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, guest.getParticipantKey())).eligibleParticipantKeys().contains(guest.getParticipantKey()));
        assertThrows(BusinessValidationException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, "foreign-key")));
        assertThrows(BusinessValidationException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey(), member.getParticipantKey())));
        member.setStatus(ParticipantStatus.REMOVED);
        assertThrows(BusinessValidationException.class, () -> service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey())));
    }

    @Test
    void cancelIsIdempotentAndCannotReopen() {
        var draft = service.create(meeting.getId(), request(MeetingPollType.YES_NO_ABSTAIN, member.getParticipantKey()));
        assertEquals(MeetingPollStatus.CANCELLED, service.cancel(meeting.getId(), draft.id()).status());
        assertEquals(MeetingPollStatus.CANCELLED, service.cancel(meeting.getId(), draft.id()).status());
        assertThrows(MeetingPollConflictException.class, () -> service.open(meeting.getId(), draft.id()));
    }

    private MeetingPollRequest request(MeetingPollType type, String... keys) {
        return new MeetingPollRequest("Approve?", null, type, false, MeetingPollResultVisibility.AFTER_CLOSE,
            1, 1, List.of(new MeetingPollRequest.OptionInput("A", null), new MeetingPollRequest.OptionInput("B", null)), List.of(keys));
    }

    private UserAccountEntity account(SystemRole role) {
        UserAccountEntity value = new UserAccountEntity();
        value.setPublicId(UUID.randomUUID().toString());
        value.setNormalizedEmail(value.getPublicId() + "@example.test");
        value.setFullName("Test account");
        value.setAccountStatus(AccountStatus.ACTIVE);
        value.setSystemRole(role);
        em.persist(value);
        return value;
    }

    private MeetingParticipantEntity participant(MeetingRole role) {
        UserAccountEntity user = account(SystemRole.USER);
        MeetingParticipantEntity value = new MeetingParticipantEntity();
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
