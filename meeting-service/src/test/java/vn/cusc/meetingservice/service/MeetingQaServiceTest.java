package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import vn.cusc.meetingservice.dto.qa.MeetingQaQuestionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaActionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaSettingsRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaUpvoteRequest;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.MeetingQaException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.MeetingQaQuestionRepository;
import vn.cusc.meetingservice.repository.MeetingQaSettingsRepository;
import vn.cusc.meetingservice.repository.MeetingQaUpvoteRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@DataJpaTest
@ActiveProfiles("test")
@Import({MeetingQaService.class, MeetingQaAuthorizationService.class, MeetingStatusResolver.class})
class MeetingQaServiceTest {
    @Autowired private MeetingQaService service;
    @Autowired private MeetingQaQuestionRepository questions;
    @Autowired private MeetingQaSettingsRepository settings;
    @Autowired private MeetingQaUpvoteRepository upvotes;
    @Autowired private EntityManager em;
    @MockBean private CurrentUserService currentUser;
    private MeetingPrincipal principal;
    private MeetingEntity meeting;
    private UserAccountEntity owner;
    private MeetingParticipantEntity member;
    private MeetingParticipantEntity secretary;
    private MeetingParticipantEntity guest;
    private String memberToken;
    private String secretaryToken;
    private String guestToken;

    @BeforeEach
    void setup() {
        owner = account(SystemRole.MEETING_MANAGER);
        meeting = new MeetingEntity();
        String key = UUID.randomUUID().toString().replace("-", "");
        meeting.setMeetingCode(key);
        meeting.setRoomName(key);
        meeting.setTitle("Q&A test");
        meeting.setStartTime(LocalDateTime.now().minusHours(1));
        meeting.setEndTime(LocalDateTime.now().plusHours(1));
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setAccessType(MeetingAccessType.PUBLIC);
        meeting.setCreatedByAccount(owner);
        em.persist(meeting);
        member = participant(MeetingRole.MEMBER, true);
        secretary = participant(MeetingRole.SECRETARY, true);
        guest = participant(MeetingRole.GUEST, false);
        memberToken = connect(member);
        secretaryToken = connect(secretary);
        guestToken = connect(guest);
        when(currentUser.getCurrentPrincipal()).thenAnswer(ignored -> Optional.ofNullable(principal));
        em.flush();
    }

    @Test
    void defaultsDoNotWriteSettingsAndGuestCanAskWithVerifiedToken() {
        var context = service.context(meeting.getId(), guestToken);
        assertTrue(context.enabled());
        assertTrue(context.anonymousAllowed());
        assertFalse(context.moderationEnabled());
        assertTrue(context.canAsk());
        assertTrue(context.canUpvote());
        assertTrue(context.canManage());
        assertEquals(0, context.settingsVersion());
        assertEquals(0, settings.count());
        var result = ask(guestToken, " Guest question ", false);
        assertEquals("Guest question", result.content());
        assertTrue(result.mine());
        assertEquals(MeetingQaStatus.OPEN, result.status());
        assertNotNull(result.createdAt());
    }

    @Test
    void sessionMembersCanReadButNeedConnectionTokenToAskOrUpvote() {
        login(member.getUserAccount());
        assertFalse(service.context(meeting.getId(), null).canAsk());
        assertFalse(service.context(meeting.getId(), null).canUpvote());
        assertEquals(0, list(null, MeetingQaFilter.ALL).totalElements());
        assertCode("QA_PARTICIPANT_TOKEN_REQUIRED", () -> ask(null, "Question", false));
        assertCode("QA_PARTICIPANT_TOKEN_REQUIRED", () -> service.upvote(meeting.getId(), 1L, null, new MeetingQaUpvoteRequest(true)));
        assertTrue(service.context(meeting.getId(), memberToken).canAsk());
    }

    @Test
    void rejectsMissingInvalidBlankAndCrossMeetingTokensWithoutSessionFallback() {
        assertCode("QA_ACCESS_FORBIDDEN", () -> service.context(meeting.getId(), null));
        login(owner);
        assertCode("QA_INVALID_PARTICIPANT_TOKEN", () -> service.context(meeting.getId(), "invalid"));
        assertCode("QA_INVALID_PARTICIPANT_TOKEN", () -> service.context(meeting.getId(), ""));
        var other = new MeetingEntity();
        other.setMeetingCode(UUID.randomUUID().toString().replace("-", ""));
        other.setRoomName(UUID.randomUUID().toString());
        other.setTitle("Other");
        other.setStartTime(meeting.getStartTime());
        other.setEndTime(meeting.getEndTime());
        other.setStatus(MeetingStatus.IN_PROGRESS);
        other.setAccessType(MeetingAccessType.PRIVATE);
        em.persist(other);
        principal = null;
        assertCode("QA_INVALID_PARTICIPANT_TOKEN", () -> service.context(other.getId(), guestToken));
    }

    @Test
    void rejectsSessionTokenMismatchAndDisabledAccount() {
        login(secretary.getUserAccount());
        assertCode("QA_PARTICIPANT_IDENTITY_MISMATCH", () -> service.context(meeting.getId(), memberToken));
        assertCode("QA_PARTICIPANT_IDENTITY_MISMATCH", () -> service.context(meeting.getId(), guestToken));
        principal = new MeetingPrincipal(member.getUserAccount().getId(), "disabled", null, "disabled@test", "Disabled", null,
            AccountStatus.DISABLED, SystemRole.USER, true, AuthProvider.LOCAL, null);
        assertCode("QA_ACCOUNT_DISABLED", () -> service.context(meeting.getId(), memberToken));
    }

    @ParameterizedTest
    @EnumSource(value = ParticipantConnectionStatus.class, names = {"LEFT", "REMOVED"})
    void rejectsDisconnectedToken(ParticipantConnectionStatus status) {
        var connection = em.createQuery("select c from MeetingParticipantConnectionEntity c where c.participant.id = :id",
            MeetingParticipantConnectionEntity.class).setParameter("id", guest.getId()).getSingleResult();
        connection.setStatus(status);
        assertCode("QA_CONNECTION_NOT_CONNECTED", () -> ask(guestToken, "Question", false));
    }

    @ParameterizedTest
    @EnumSource(MeetingRole.class)
    void joinIssuedPendingTokenCanParticipateForEveryRoleWithoutChangingConnectionState(MeetingRole role) {
        var candidate = participant(role, role != MeetingRole.GUEST);
        candidate.setStatus(ParticipantStatus.INVITED);
        String token = connect(candidate);
        var connection = em.createQuery("select c from MeetingParticipantConnectionEntity c where c.participant.id = :id",
            MeetingParticipantConnectionEntity.class).setParameter("id", candidate.getId()).getSingleResult();
        connection.setStatus(ParticipantConnectionStatus.PENDING);
        if (candidate.getUserAccount() != null) login(candidate.getUserAccount());
        em.flush();

        var context = service.context(meeting.getId(), token);
        assertTrue(context.canAsk());
        assertTrue(context.canUpvote());
        assertTrue(context.canManage());
        var request = request(" Question before webhook ", false);
        var question = service.ask(meeting.getId(), token, request);
        assertEquals("Question before webhook", question.content());
        assertEquals(question, service.ask(meeting.getId(), token, request));
        assertEquals(1, service.upvote(meeting.getId(), question.id(), token, new MeetingQaUpvoteRequest(true)).upvoteCount());
        assertEquals(1, questions.count());
        assertEquals(ParticipantConnectionStatus.PENDING, connection.getStatus());
        assertNull(connection.getJoinedAt());
        assertEquals(ParticipantStatus.INVITED, candidate.getStatus());
        if (!context.canManage()) {
            assertCode("QA_MODERATION_FORBIDDEN", () -> service.updateSettings(meeting.getId(), token,
                new MeetingQaSettingsRequest(false, true, false, 0L)));
        }
    }

    @Test
    void pendingTokenStillRejectsRemovedAndDisabledParticipantsAndIdentityMismatch() {
        var connection = em.createQuery("select c from MeetingParticipantConnectionEntity c where c.participant.id = :id",
            MeetingParticipantConnectionEntity.class).setParameter("id", member.getId()).getSingleResult();
        connection.setStatus(ParticipantConnectionStatus.PENDING);
        login(secretary.getUserAccount());
        assertCode("QA_PARTICIPANT_IDENTITY_MISMATCH", () -> service.context(meeting.getId(), memberToken));
        principal = null;
        member.getUserAccount().setAccountStatus(AccountStatus.DISABLED);
        assertCode("QA_ACCOUNT_DISABLED", () -> ask(memberToken, "Question", false));
        member.getUserAccount().setAccountStatus(AccountStatus.ACTIVE);
        member.setStatus(ParticipantStatus.REMOVED);
        assertCode("QA_PARTICIPANT_REMOVED", () -> ask(memberToken, "Question", false));
        assertEquals(0, questions.count());
    }

    @Test
    void databaseAccountRevocationInvalidatesBearerAndCachedSession() {
        login(member.getUserAccount());
        member.getUserAccount().setAccountStatus(AccountStatus.DISABLED);
        assertCode("QA_ACCOUNT_DISABLED", () -> service.context(meeting.getId(), null));
        principal = null;
        assertCode("QA_ACCOUNT_DISABLED", () -> service.context(meeting.getId(), memberToken));
        assertTrue(service.context(meeting.getId(), guestToken).canAsk());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingQaStatus.class, names = {"HIDDEN", "DELETED"})
    void retryKeepsHiddenOrDeletedStatusWithoutRestoringQuestion(MeetingQaStatus status) {
        var request = request("Question", false);
        var question = service.ask(meeting.getId(), memberToken, request);
        questions.findById(question.id()).orElseThrow().setStatus(status);
        assertEquals(status, service.ask(meeting.getId(), memberToken, request).status());
        assertEquals(status, questions.findById(question.id()).orElseThrow().getStatus());
    }

    @Test
    void rejectsRemovedParticipantIncludingSessionSecretary() {
        guest.setStatus(ParticipantStatus.REMOVED);
        assertCode("QA_PARTICIPANT_REMOVED", () -> service.context(meeting.getId(), guestToken));
        secretary.setStatus(ParticipantStatus.REMOVED);
        login(secretary.getUserAccount());
        assertFalse(service.context(meeting.getId(), null).canManage());
        assertFalse(service.context(meeting.getId(), null).canAsk());
    }

    @ParameterizedTest
    @EnumSource(MeetingRole.class)
    void validParticipantCredentialAllowsManagementForEveryRole(MeetingRole role) {
        var candidate = participant(role, true);
        String token = connect(candidate);
        assertTrue(service.context(meeting.getId(), token).canManage());
        login(candidate.getUserAccount());
        assertTrue(service.context(meeting.getId(), token).canManage());
    }

    @Test
    void publicMeetingAllowsUnrelatedViewerWithoutGrantingParticipantOrModeratorActions() {
        login(account(SystemRole.MEETING_MANAGER));
        assertFalse(service.context(meeting.getId(), null).canManage());
        assertFalse(service.context(meeting.getId(), null).canAsk());
        login(owner);
        assertTrue(service.context(meeting.getId(), null).canManage());
        assertFalse(service.context(meeting.getId(), null).canAsk());
        login(account(SystemRole.ADMIN));
        assertFalse(service.context(meeting.getId(), null).canManage());
    }

    @Test
    void anonymousNameAndInternalIdentifiersNeverLeakToModeratorOrOtherParticipant() throws Exception {
        var mine = ask(guestToken, "Anonymous question", true);
        assertEquals("Ẩn danh", mine.authorDisplayName());
        assertTrue(mine.mine());
        assertNull(questions.findById(mine.id()).orElseThrow().getAuthorDisplayName());
        login(owner);
        var result = list(null, MeetingQaFilter.ALL).content().get(0);
        assertEquals("Ẩn danh", result.authorDisplayName());
        assertFalse(result.mine());
        assertNull(result.clientQuestionId());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertFalse(json.contains(guest.getFullName()));
        assertFalse(json.contains(guest.getParticipantKey()));
        assertFalse(json.contains("participantId"));
        assertFalse(json.contains("connectionId"));
        assertFalse(json.contains("accountId"));
        assertFalse(json.contains("token"));
        principal = null;
        assertFalse(list(memberToken, MeetingQaFilter.ALL).content().get(0).mine());
    }

    @Test
    void privacyFiltersRunBeforePaginationAndTotals() {
        var config = new MeetingQaSettingsEntity();
        config.setMeeting(meeting);
        config.setModerationEnabled(true);
        settings.saveAndFlush(config);
        var own = ask(memberToken, "My pending", false);
        ask(guestToken, "Other pending", true);
        var hidden = questions.findById(ask(guestToken, "Hidden", false).id()).orElseThrow();
        hidden.setStatus(MeetingQaStatus.HIDDEN);
        var deleted = questions.findById(ask(guestToken, "Deleted", false).id()).orElseThrow();
        deleted.setStatus(MeetingQaStatus.DELETED);
        config.setModerationEnabled(false);
        ask(guestToken, "Public", false);
        var result = service.list(meeting.getId(), memberToken, 0, 1, MeetingQaSort.OLDEST, MeetingQaFilter.ALL);
        assertEquals(5, result.totalElements());
        assertEquals(5, result.totalPages());
        assertEquals(own.id(), result.content().get(0).id());
        assertEquals(1, list(memberToken, MeetingQaFilter.MINE).totalElements());
        assertEquals(2, list(memberToken, MeetingQaFilter.PENDING).totalElements());
        login(account(SystemRole.USER));
        assertEquals(1, list(null, MeetingQaFilter.ALL).totalElements());
        assertCode("QA_MODERATION_FORBIDDEN", () -> list(null, MeetingQaFilter.PENDING));
        login(owner);
        assertEquals(5, list(null, MeetingQaFilter.ALL).totalElements());
        assertEquals(2, list(null, MeetingQaFilter.PENDING).totalElements());
    }

    @Test
    void identicalRetrySurvivesDisabledOrEndedMeetingAndChangedRetryConflicts() {
        var request = request("Question", false);
        var first = service.ask(meeting.getId(), memberToken, request);
        var config = new MeetingQaSettingsEntity();
        config.setMeeting(meeting);
        config.setEnabled(false);
        settings.saveAndFlush(config);
        meeting.setStatus(MeetingStatus.COMPLETED);
        assertEquals(first.id(), service.ask(meeting.getId(), memberToken, request).id());
        assertEquals(1, questions.count());
        assertCode("QA_CONFLICT", () -> service.ask(meeting.getId(), memberToken,
            new MeetingQaQuestionRequest(request.clientQuestionId(), "Different", false)));
        assertCode("QA_CONFLICT", () -> service.ask(meeting.getId(), memberToken,
            new MeetingQaQuestionRequest(request.clientQuestionId(), "Question", true)));
    }

    @Test
    void retriesAreScopedPerParticipantAndRecoveredAcrossConnections() {
        var request = request("Question", false);
        var first = service.ask(meeting.getId(), memberToken, request);
        assertEquals(first.id(), service.ask(meeting.getId(), connect(member), request).id());
        assertNotEquals(first.id(), service.ask(meeting.getId(), guestToken, request).id());
        assertEquals(2, questions.count());
    }

    @Test
    void settingsApplyOnlyToNewQuestionsAndNeverRevealExistingAnonymousAuthor() {
        ask(guestToken, "Old anonymous", true);
        var config = new MeetingQaSettingsEntity();
        config.setMeeting(meeting);
        config.setAnonymousAllowed(false);
        settings.saveAndFlush(config);
        assertCode("QA_CONFLICT", () -> ask(guestToken, "New anonymous", true));
        assertEquals("Ẩn danh", list(memberToken, MeetingQaFilter.ALL).content().get(0).authorDisplayName());
        config.setEnabled(false);
        assertFalse(service.context(meeting.getId(), memberToken).canAsk());
        assertCode("QA_CONFLICT", () -> ask(memberToken, "New", false));
        assertEquals(1, list(memberToken, MeetingQaFilter.ALL).totalElements());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalMeetingsKeepHistoryButRejectNewWrites(MeetingStatus status) {
        var question = ask(memberToken, "Question", false);
        meeting.setStatus(status);
        assertEquals(1, list(memberToken, MeetingQaFilter.ALL).totalElements());
        assertFalse(service.context(meeting.getId(), memberToken).canUpvote());
        assertCode("QA_CONFLICT", () -> ask(memberToken, "New", false));
        assertCode("QA_CONFLICT", () -> service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(true)));
    }

    @Test
    void scheduledUpcomingAndTimeExpiredMeetingsRejectWrites() {
        meeting.setStartTime(LocalDateTime.now().plusHours(1));
        meeting.setEndTime(LocalDateTime.now().plusHours(2));
        assertCode("QA_CONFLICT", () -> ask(memberToken, "Upcoming", false));
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusHours(1));
        assertCode("QA_CONFLICT", () -> ask(memberToken, "Expired", false));
    }

    @Test
    void votesUseDesiredStateAndRemainUniqueIncludingOwnAndAnsweredQuestion() {
        var question = ask(memberToken, "Question", false);
        var first = service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(true));
        var retry = service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(true));
        assertEquals(1, first.upvoteCount());
        assertEquals(first.version(), retry.version());
        assertTrue(retry.upvotedByMe());
        assertEquals(1, upvotes.count());
        var second = service.upvote(meeting.getId(), question.id(), guestToken, new MeetingQaUpvoteRequest(true));
        assertEquals(2, second.upvoteCount());
        questions.findById(question.id()).orElseThrow().setStatus(MeetingQaStatus.ANSWERED);
        questions.findById(question.id()).orElseThrow().setAnsweredAt(LocalDateTime.now());
        var removed = service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(false));
        assertEquals(1, removed.upvoteCount());
        assertFalse(removed.upvotedByMe());
        assertEquals(removed.version(), service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(false)).version());
        assertTrue(list(guestToken, MeetingQaFilter.ALL).content().get(0).upvotedByMe());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingQaStatus.class, names = {"PENDING", "HIDDEN", "DELETED"})
    void privateQuestionsCannotBeUpvotedOrProbed(MeetingQaStatus status) {
        var question = ask(guestToken, "Private", false);
        questions.findById(question.id()).orElseThrow().setStatus(status);
        assertCode("QA_QUESTION_NOT_FOUND", () -> service.upvote(meeting.getId(), question.id(), memberToken, new MeetingQaUpvoteRequest(true)));
        assertEquals(0, upvotes.count());
    }

    @Test
    void sortingAndPaginationStayDeterministic() {
        var first = ask(memberToken, "First", false);
        var second = ask(memberToken, "Second", false);
        service.upvote(meeting.getId(), first.id(), guestToken, new MeetingQaUpvoteRequest(true));
        assertEquals(first.id(), service.list(meeting.getId(), guestToken, 0, 1, MeetingQaSort.POPULAR, MeetingQaFilter.ALL).content().get(0).id());
        assertEquals(first.id(), service.list(meeting.getId(), guestToken, 0, 1, MeetingQaSort.OLDEST, MeetingQaFilter.ALL).content().get(0).id());
        assertEquals(second.id(), service.list(meeting.getId(), guestToken, 0, 1, MeetingQaSort.NEWEST, MeetingQaFilter.ALL).content().get(0).id());
        assertEquals(0, service.list(meeting.getId(), guestToken, 4, 20, MeetingQaSort.NEWEST, MeetingQaFilter.ALL).content().size());
        assertCode("QA_VALIDATION_ERROR", () -> service.list(meeting.getId(), guestToken, -1, 20, MeetingQaSort.NEWEST, MeetingQaFilter.ALL));
        assertCode("QA_VALIDATION_ERROR", () -> service.list(meeting.getId(), guestToken, 0, 51, MeetingQaSort.NEWEST, MeetingQaFilter.ALL));
    }

    @Test
    void validatesUuidTrimmedContentAndDesiredVote() {
        assertCode("QA_VALIDATION_ERROR", () -> service.ask(meeting.getId(), memberToken, new MeetingQaQuestionRequest("1-1-1-1-1", "Question", false)));
        assertCode("QA_VALIDATION_ERROR", () -> ask(memberToken, " ", false));
        assertCode("QA_VALIDATION_ERROR", () -> ask(memberToken, "x".repeat(2001), false));
        assertCode("QA_VALIDATION_ERROR", () -> service.upvote(meeting.getId(), 1L, memberToken, new MeetingQaUpvoteRequest(null)));
    }

    @Test
    void paginationRejectsJpaOffsetOverflow() {
        assertCode("QA_VALIDATION_ERROR", () -> service.list(meeting.getId(), memberToken,
            Integer.MAX_VALUE, 50, MeetingQaSort.NEWEST, MeetingQaFilter.ALL));
    }

    @Test
    void sendAndVoteReceiptsUsePersistedTimestampPrecisionAcrossReload() {
        var request = request("Stable receipt", false);
        var first = service.ask(meeting.getId(), memberToken, request);
        em.clear();
        var retry = service.ask(meeting.getId(), memberToken, request);
        assertEquals(first.createdAt(), retry.createdAt());
        assertEquals(first.updatedAt(), retry.updatedAt());
        var voted = service.upvote(meeting.getId(), first.id(), guestToken, new MeetingQaUpvoteRequest(true));
        em.clear();
        var voteRetry = service.upvote(meeting.getId(), first.id(), guestToken, new MeetingQaUpvoteRequest(true));
        assertEquals(voted.updatedAt(), voteRetry.updatedAt());
        assertEquals(voted.version(), voteRetry.version());
    }

    @Test
    void settingsCompareVersionAndCanReenableDisabledQaWithoutCreatingNoOpRows() {
        login(owner);
        var defaults = service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(true, true, false, 0L));
        assertEquals(0, defaults.settingsVersion());
        assertEquals(0, settings.count());
        var disabled = service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(false, false, true, 0L));
        assertFalse(disabled.enabled());
        assertFalse(disabled.anonymousAllowed());
        assertTrue(disabled.moderationEnabled());
        assertEquals(1, disabled.settingsVersion());
        assertEquals(1, settings.count());
        assertCode("QA_CONFLICT", () -> service.updateSettings(meeting.getId(), null,
            new MeetingQaSettingsRequest(true, true, false, 0L)));
        var noop = service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(false, false, true, 1L));
        assertEquals(1, noop.settingsVersion());
        var enabled = service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(true, false, true, 1L));
        assertTrue(enabled.enabled());
        assertEquals(2, enabled.settingsVersion());
    }

    @Test
    void turningModerationAndAnonymityOffPreservesPendingAnonymousQuestion() {
        login(owner);
        service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(true, true, true, 0L));
        principal = null;
        var question = ask(guestToken, "Awaiting review", true);
        assertEquals(MeetingQaStatus.PENDING, question.status());
        login(owner);
        service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(true, false, false, 1L));
        var pending = list(null, MeetingQaFilter.PENDING).content().get(0);
        assertEquals(question.id(), pending.id());
        assertEquals("Ẩn danh", pending.authorDisplayName());
        assertNull(pending.clientQuestionId());
        principal = null;
        assertCode("QA_CONFLICT", () -> ask(guestToken, "New anonymous", true));
        assertEquals(MeetingQaStatus.OPEN, ask(guestToken, "New visible", false).status());
        assertEquals(2, list(memberToken, MeetingQaFilter.ALL).totalElements());
    }

    @ParameterizedTest
    @EnumSource(MeetingRole.class)
    void everyParticipantRoleMayManageIncludingTokenOnlyGuest(MeetingRole role) {
        var question = ask(guestToken, "Question", false);
        var candidate = participant(role, role != MeetingRole.GUEST);
        String token = connect(candidate);
        if (candidate.getUserAccount() != null) login(candidate.getUserAccount());
        service.updateSettings(meeting.getId(), token, new MeetingQaSettingsRequest(false, true, false, 0L));
        assertEquals(MeetingQaStatus.ANSWERED, service.moderate(meeting.getId(), question.id(), token,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L)).status());
        principal = null;
        service.updateSettings(meeting.getId(), token, new MeetingQaSettingsRequest(true, true, false, 1L));
        assertEquals(MeetingQaStatus.DELETED, service.moderate(meeting.getId(), question.id(), token,
            new MeetingQaActionRequest(MeetingQaAction.DELETE, 1L)).status());
    }

    @Test
    void participantAndOwnerCanModerateButUninvitedAdminAndInvalidTokensCannot() {
        var question = ask(guestToken, "Question", true);
        login(account(SystemRole.ADMIN));
        assertCode("QA_MODERATION_FORBIDDEN", () -> service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L)));
        login(member.getUserAccount());
        var answered = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L));
        assertEquals("Ẩn danh", answered.authorDisplayName());
        login(owner);
        assertEquals(MeetingQaStatus.HIDDEN, service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.HIDE, answered.version())).status());
        assertCode("QA_INVALID_PARTICIPANT_TOKEN", () -> service.updateSettings(meeting.getId(), "invalid",
            new MeetingQaSettingsRequest(false, false, false, 0L)));
    }

    @ParameterizedTest
    @MethodSource("actionMatrix")
    void moderatorActionsFollowCompleteTransitionMatrix(MeetingQaAction action, MeetingQaStatus source, boolean allowed) {
        var receipt = ask(guestToken, "Question", true);
        var row = questions.findById(receipt.id()).orElseThrow();
        row.setStatus(source);
        if (source == MeetingQaStatus.ANSWERED) row.setAnsweredAt(LocalDateTime.now());
        em.flush();
        login(owner);
        var request = new MeetingQaActionRequest(action, 0L);
        if (!allowed) {
            assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), receipt.id(), null, request));
            assertEquals(source, row.getStatus());
            return;
        }
        var result = service.moderate(meeting.getId(), receipt.id(), null, request);
        var target = switch (action) {
            case APPROVE -> MeetingQaStatus.OPEN;
            case ANSWER -> MeetingQaStatus.ANSWERED;
            case HIDE -> MeetingQaStatus.HIDDEN;
            case DELETE -> MeetingQaStatus.DELETED;
        };
        assertEquals(target, result.status());
        assertEquals(source == target ? 0 : 1, result.version());
        assertEquals("Ẩn danh", result.authorDisplayName());
        assertNull(result.clientQuestionId());
        if (target == MeetingQaStatus.ANSWERED) assertNotNull(result.answeredAt());
    }

    private static Stream<Arguments> actionMatrix() {
        return Stream.of(MeetingQaAction.values()).flatMap(action -> Stream.of(MeetingQaStatus.values()).map(status -> {
            boolean allowed = switch (action) {
                case APPROVE -> status == MeetingQaStatus.PENDING || status == MeetingQaStatus.OPEN;
                case ANSWER -> status == MeetingQaStatus.OPEN || status == MeetingQaStatus.ANSWERED;
                case HIDE -> status != MeetingQaStatus.DELETED;
                case DELETE -> true;
            };
            return Arguments.of(action, status, allowed);
        }));
    }

    @Test
    void moderatorLifecyclePreservesAnswerTimestampAndRequiresFreshVersion() {
        var receipt = ask(guestToken, "Question", true);
        var row = questions.findById(receipt.id()).orElseThrow();
        row.setStatus(MeetingQaStatus.PENDING);
        login(owner);
        var approved = service.moderate(meeting.getId(), receipt.id(), null, new MeetingQaActionRequest(MeetingQaAction.APPROVE, 0L));
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), receipt.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L)));
        var answered = service.moderate(meeting.getId(), receipt.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, approved.version()));
        em.clear();
        var retry = service.moderate(meeting.getId(), receipt.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, answered.version()));
        assertEquals(answered.answeredAt(), retry.answeredAt());
        assertEquals(answered.updatedAt(), retry.updatedAt());
        assertEquals(answered.version(), retry.version());
        var hidden = service.moderate(meeting.getId(), receipt.id(), null, new MeetingQaActionRequest(MeetingQaAction.HIDE, retry.version()));
        var deleted = service.moderate(meeting.getId(), receipt.id(), null, new MeetingQaActionRequest(MeetingQaAction.DELETE, hidden.version()));
        assertEquals(answered.answeredAt(), deleted.answeredAt());
        principal = null;
        assertEquals(MeetingQaStatus.DELETED, list(guestToken, MeetingQaFilter.ALL).content().get(0).status());
        login(account(SystemRole.USER));
        assertEquals(0, list(null, MeetingQaFilter.ALL).totalElements());
    }

    @Test
    void voteChangeInvalidatesOlderModeratorVersionWithoutLosingVote() {
        var question = ask(memberToken, "Question", false);
        var voted = service.upvote(meeting.getId(), question.id(), guestToken, new MeetingQaUpvoteRequest(true));
        login(owner);
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, question.version())));
        var answered = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, voted.version()));
        assertEquals(1, answered.upvoteCount());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalMeetingsRejectModeratorMutationsEvenForOwner(MeetingStatus status) {
        var question = ask(memberToken, "Question", false);
        meeting.setStatus(status);
        login(owner);
        assertCode("QA_CONFLICT", () -> service.updateSettings(meeting.getId(), null,
            new MeetingQaSettingsRequest(false, false, false, 0L)));
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.DELETE, 0L)));
    }

    @Test
    void moderatorPayloadValidationRejectsMissingFieldsAndNegativeVersions() {
        login(owner);
        assertCode("QA_VALIDATION_ERROR", () -> service.updateSettings(meeting.getId(), null,
            new MeetingQaSettingsRequest(null, true, false, 0L)));
        assertCode("QA_VALIDATION_ERROR", () -> service.moderate(meeting.getId(), 1L, null,
            new MeetingQaActionRequest(null, 0L)));
        assertCode("QA_VALIDATION_ERROR", () -> service.moderate(meeting.getId(), 1L, null,
            new MeetingQaActionRequest(MeetingQaAction.HIDE, -1L)));
    }

    @ParameterizedTest
    @EnumSource(value = MeetingStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void reportIsReadOnlyForLiveAndTerminalMeetingsIncludingDisabledQa(MeetingStatus status) {
        var question = ask(guestToken, "Report question", true);
        login(owner);
        service.updateSettings(meeting.getId(), null, new MeetingQaSettingsRequest(false, false, true, 0L));
        meeting.setStatus(status);
        em.flush();
        var before = questions.findById(question.id()).orElseThrow();
        var updatedAt = before.getUpdatedAt();
        var report = service.report(meeting.getId(), null);
        assertTrue(report.startsWith("\uFEFF\"Nội dung câu hỏi\""));
        assertTrue(report.contains("\"Report question\",\"Ẩn danh\",\"Chưa trả lời\",\"0\""));
        assertFalse(report.contains(guest.getFullName()));
        assertFalse(report.contains(guest.getEmail()));
        assertFalse(report.contains(guest.getParticipantKey()));
        assertFalse(report.contains(question.clientQuestionId()));
        em.clear();
        var after = questions.findById(question.id()).orElseThrow();
        assertEquals(question.version(), after.getVersion());
        assertEquals(updatedAt, after.getUpdatedAt());
        assertEquals(1, settings.findByMeetingId(meeting.getId()).orElseThrow().getVersion());
        assertEquals(0, upvotes.count());
    }

    @Test
    void reportAvailableToParticipantsButInvalidTokenNeverFallsBack() {
        ask(memberToken, "Question", false);
        assertTrue(service.report(meeting.getId(), guestToken).contains("Question"));
        assertTrue(service.report(meeting.getId(), secretaryToken).contains("Question"));
        login(member.getUserAccount());
        assertTrue(service.report(meeting.getId(), null).contains("Question"));
        login(owner);
        assertCode("QA_INVALID_PARTICIPANT_TOKEN", () -> service.report(meeting.getId(), "invalid"));
        login(account(SystemRole.ADMIN));
        assertCode("QA_MODERATION_FORBIDDEN", () -> service.report(meeting.getId(), null));
    }

    @Test
    void reportIncludesAllStatusesWithoutInternalIdentityOrAnonymousName() {
        for (var status : MeetingQaStatus.values()) {
            var receipt = ask(guestToken, "State " + status, true);
            var row = questions.findById(receipt.id()).orElseThrow();
            row.setStatus(status);
            if (status == MeetingQaStatus.ANSWERED) row.setAnsweredAt(LocalDateTime.of(2026, 10, 6, 1, 0));
        }
        login(owner);
        String report = service.report(meeting.getId(), null);
        assertTrue(report.contains("Chờ duyệt"));
        assertTrue(report.contains("Chưa trả lời"));
        assertTrue(report.contains("Đã trả lời"));
        assertTrue(report.contains("Đã ẩn"));
        assertTrue(report.contains("Đã xóa"));
        assertTrue(report.contains("06/10/2026 01:00:00"));
        assertEquals(6, report.split("\r\n").length);
        assertFalse(report.contains("participant"));
        assertFalse(report.contains("clientQuestionId"));
        assertFalse(report.contains(guest.getFullName()));
    }

    @Test
    void reportEscapesUntrustedQuestionAndNamedAuthor() {
        guest.setFullName("\u200B=HYPERLINK(\"https://evil.test\")");
        ask(guestToken, "\u00A0+SUM(1,2)", false);
        login(owner);
        String report = service.report(meeting.getId(), null);
        assertTrue(report.contains("\"'\u00A0+SUM(1,2)\""));
        assertTrue(report.contains("\"'\u200B=HYPERLINK(\"\"https://evil.test\"\")\""));
    }

    @Test
    void reportEmptyMeetingHasOnlyBomAndHeader() {
        login(owner);
        String report = service.report(meeting.getId(), null);
        assertTrue(report.startsWith("\uFEFF"));
        assertEquals(1, report.split("\r\n").length);
        assertEquals(0, questions.count());
    }

    @Test
    void reportTraversesMultipleBoundedBatchesInDeterministicOrder() {
        for (int index = 0; index < 205; index++) {
            var row = new MeetingQaQuestionEntity();
            row.setMeeting(meeting);
            row.setParticipant(guest);
            row.setClientQuestionId(UUID.randomUUID().toString());
            row.setContent(String.format("Batch question %03d", index));
            row.setAnonymous(true);
            em.persist(row);
        }
        em.flush();
        login(owner);
        String report = service.report(meeting.getId(), null);
        assertEquals(206, report.split("\r\n").length);
        assertTrue(report.indexOf("Batch question 000") < report.indexOf("Batch question 199"));
        assertTrue(report.indexOf("Batch question 199") < report.indexOf("Batch question 204"));
    }

    @ParameterizedTest
    @EnumSource(MeetingRole.class)
    void writtenAnswerIsPersistedForEveryParticipantRoleWithoutRevealingAnonymousAsker(MeetingRole role) {
        var question = ask(guestToken, "Anonymous question", true);
        var responder = participant(role, role != MeetingRole.GUEST);
        String token = connect(responder);
        var answer = service.moderate(meeting.getId(), question.id(), token,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L, "  Nội dung trả lời\nDòng thứ hai  "));
        assertEquals(MeetingQaStatus.ANSWERED, answer.status());
        assertEquals("Nội dung trả lời\nDòng thứ hai", answer.answer());
        assertEquals(responder.getFullName(), answer.answerAuthorDisplayName());
        assertEquals("Ẩn danh", answer.authorDisplayName());
        assertEquals(1, answer.version());
        assertNotNull(answer.answeredAt());
        em.clear();
        var reloaded = list(memberToken, MeetingQaFilter.ALL).content().get(0);
        assertEquals(answer.answer(), reloaded.answer());
        assertEquals(answer.answerAuthorDisplayName(), reloaded.answerAuthorDisplayName());
        assertEquals(answer.answeredAt(), reloaded.answeredAt());
    }

    @Test
    void ownerCanFillLegacyAnswerEditWithFreshVersionAndRepeatWithoutChangingReceipt() {
        var question = ask(guestToken, "Question", true);
        login(owner);
        var legacy = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L));
        assertNull(legacy.answer());
        var first = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, legacy.version(), "First answer"));
        assertEquals(owner.getFullName(), first.answerAuthorDisplayName());
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, legacy.version(), "Stale answer")));
        var edited = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, first.version(), "Edited answer"));
        assertEquals("Edited answer", edited.answer());
        em.clear();
        var unchanged = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, edited.version(), "Edited answer"));
        assertEquals(edited, unchanged);
        var hidden = service.moderate(meeting.getId(), question.id(), null,
            new MeetingQaActionRequest(MeetingQaAction.HIDE, unchanged.version()));
        assertEquals(edited.answer(), hidden.answer());
        assertEquals(edited.answeredAt(), hidden.answeredAt());
    }

    @Test
    void invalidWrittenAnswersDoNotMutateTheQuestion() {
        var question = ask(memberToken, "Question", false);
        for (String invalidAnswer : java.util.List.of("", "   ", "x".repeat(5001))) {
            assertCode("QA_VALIDATION_ERROR", () -> service.moderate(meeting.getId(), question.id(), guestToken,
                new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L, invalidAnswer)));
        }
        assertCode("QA_VALIDATION_ERROR", () -> service.moderate(meeting.getId(), question.id(), guestToken,
            new MeetingQaActionRequest(MeetingQaAction.HIDE, 0L, "Misplaced answer")));
        assertEquals(0, questions.findById(question.id()).orElseThrow().getVersion());
        assertNull(questions.findById(question.id()).orElseThrow().getAnswer());
        var maximum = service.moderate(meeting.getId(), question.id(), guestToken,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L, "x".repeat(5000)));
        assertEquals(5000, maximum.answer().length());
    }

    @ParameterizedTest
    @EnumSource(value = MeetingQaStatus.class, names = {"PENDING", "HIDDEN", "DELETED"})
    void writtenAnswersCannotPublishInvisibleQuestions(MeetingQaStatus status) {
        var question = ask(memberToken, "Question", false);
        questions.findById(question.id()).orElseThrow().setStatus(status);
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), question.id(), guestToken,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L, "Answer")));
    }

    @Test
    void writtenAnswerRemainsReadableAfterMeetingEndAndCsvEscapesResponderAndAnswer() {
        var question = ask(guestToken, "Anonymous question", true);
        member.setFullName("=Responder");
        var answer = service.moderate(meeting.getId(), question.id(), memberToken,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, 0L, "=Answer\nSecond line"));
        meeting.setStatus(MeetingStatus.COMPLETED);
        em.flush(); em.clear();
        assertEquals(answer.answer(), list(guestToken, MeetingQaFilter.ALL).content().get(0).answer());
        assertCode("QA_CONFLICT", () -> service.moderate(meeting.getId(), question.id(), memberToken,
            new MeetingQaActionRequest(MeetingQaAction.ANSWER, answer.version(), "After end")));
        String report = service.report(meeting.getId(), memberToken);
        assertTrue(report.contains("Nội dung trả lời"));
        assertTrue(report.contains("Người trả lời"));
        assertTrue(report.contains("\"'=Answer\nSecond line\""));
        assertTrue(report.contains("\"'=Responder\""));
        assertFalse(report.contains(guest.getFullName()));
    }

    private vn.cusc.meetingservice.dto.qa.MeetingQaQuestionResponse ask(String token, String content, boolean anonymous) {
        return service.ask(meeting.getId(), token, request(content, anonymous));
    }
    private MeetingQaQuestionRequest request(String content, boolean anonymous) {
        return new MeetingQaQuestionRequest(UUID.randomUUID().toString(), content, anonymous);
    }
    private vn.cusc.meetingservice.dto.response.PageResponse<vn.cusc.meetingservice.dto.qa.MeetingQaQuestionResponse> list(String token, MeetingQaFilter filter) {
        return service.list(meeting.getId(), token, 0, 20, MeetingQaSort.NEWEST, filter);
    }
    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(MeetingQaException.class, action::run).code());
    }
    private UserAccountEntity account(SystemRole role) {
        var value = new UserAccountEntity();
        value.setPublicId(UUID.randomUUID().toString());
        value.setNormalizedEmail(value.getPublicId() + "@qa.test");
        value.setFullName("Account " + role);
        value.setAccountStatus(AccountStatus.ACTIVE);
        value.setSystemRole(role);
        em.persist(value);
        return value;
    }
    private MeetingParticipantEntity participant(MeetingRole role, boolean authenticated) {
        var value = new MeetingParticipantEntity();
        value.setMeeting(meeting);
        value.setParticipantKey(UUID.randomUUID().toString());
        value.setFullName("Unique " + role + " " + UUID.randomUUID());
        value.setEmail(UUID.randomUUID() + "@qa.test");
        value.setRole(role);
        value.setStatus(ParticipantStatus.JOINED);
        value.setJoinType(authenticated ? ParticipantJoinType.AUTHENTICATED : ParticipantJoinType.GUEST);
        if (authenticated) value.setUserAccount(account(SystemRole.USER));
        em.persist(value);
        return value;
    }
    private String connect(MeetingParticipantEntity participant) {
        String token = MeetingChatTokenUtils.generateToken();
        var value = new MeetingParticipantConnectionEntity();
        value.setMeeting(meeting);
        value.setParticipant(participant);
        value.setAttendeeId(participant.getParticipantKey());
        value.setConnectionId(UUID.randomUUID().toString());
        value.setLiveKitIdentity(UUID.randomUUID().toString());
        value.setChatTokenHash(MeetingChatTokenUtils.sha256(token));
        value.setStatus(ParticipantConnectionStatus.CONNECTED);
        em.persist(value);
        return token;
    }
    private void login(UserAccountEntity user) {
        principal = new MeetingPrincipal(user.getId(), user.getPublicId(), null, user.getNormalizedEmail(), user.getFullName(), null,
            AccountStatus.ACTIVE, user.getSystemRole(), true, AuthProvider.LOCAL, null);
    }
}
