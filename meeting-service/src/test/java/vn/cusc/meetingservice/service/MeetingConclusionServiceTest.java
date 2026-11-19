package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;
import vn.cusc.meetingservice.dto.request.*;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.*;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.security.*;
import vn.cusc.meetingservice.service.impl.MeetingMinutesServiceImpl;
import vn.cusc.meetingservice.mapper.MeetingMinutesMapper;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@DataJpaTest @ActiveProfiles("test")
@Import({MeetingConclusionService.class, ConclusionContextBuilder.class, ConclusionResultValidator.class,
    MeetingMinutesAuthorizationService.class, MeetingStatusResolver.class, MeetingMinutesServiceImpl.class,
    MeetingMinutesMapper.class, MeetingConclusionServiceTest.Beans.class})
class MeetingConclusionServiceTest {
    @TestConfiguration static class Beans {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Validator validator() { return new LocalValidatorFactoryBean(); }
    }
    @Autowired MeetingConclusionService service;
    @Autowired MeetingMinutesServiceImpl minutesService;
    @Autowired MeetingConclusionRepository conclusions;
    @Autowired MeetingMinutesRepository minutes;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    @MockBean CurrentUserService current;
    MeetingEntity meeting; MeetingMinutesEntity source; UserAccountEntity owner; MeetingPrincipal principal;

    @BeforeEach void setup() {
        owner = account("Owner"); login(owner);
        when(current.getCurrentPrincipal()).thenAnswer(ignored -> Optional.ofNullable(principal));
        meeting = new MeetingEntity(); meeting.setMeetingCode(UUID.randomUUID().toString().replace("-", ""));
        meeting.setRoomName(UUID.randomUUID().toString()); meeting.setTitle("Conclusion test");
        meeting.setCreatedByAccount(owner); meeting.setAccessType(MeetingAccessType.PUBLIC);
        meeting.setStartTime(LocalDateTime.now().minusHours(2)); meeting.setEndTime(LocalDateTime.now().minusHours(1));
        meeting.setStatus(MeetingStatus.COMPLETED); em.persist(meeting);
        source = new MeetingMinutesEntity(); source.setMeeting(meeting); source.setVersion(1);
        source.setStatus(MinutesStatus.DRAFT); source.setSource(MinutesVersionSource.OWNER_EDITED);
        source.setMinutesJson("{\"mainContent\":\"Chủ tọa thống nhất triển khai.\",\"conclusion\":\"Manual original\"}");
        source.setGeneratedAt(LocalDateTime.now()); source.setCreatedByAccount(owner); em.persist(source); em.flush();
    }
    Generate input() { return new Generate(1, "Manual original", "Manual original"); }
    Result result() { return new Result("Summary", List.of(new Item("Decision", List.of("minutes.v1"))), List.of(), List.of(), List.of()); }
    ProviderResponse receipt(Work work) { return new ProviderResponse(work.generationId(), result()); }

    @Test void durableJobSnapshotsManualAndRejectsDuplicateSuccessWhilePreservingOriginalMinutes() throws Exception {
        View queued = service.generate(meeting.getId(), input());
        assertEquals("GENERATING", queued.status()); assertEquals("QUEUED", queued.stage());
        assertEquals(queued.generationId(), service.generate(meeting.getId(), input()).generationId());
        assertEquals(1, conclusions.count());
        Work work = service.claim(meeting.getId()).orElseThrow();
        assertTrue(service.claim(meeting.getId()).isEmpty());
        assertTrue(service.complete(work, receipt(work))); assertFalse(service.complete(work, receipt(work)));
        em.clear();
        var saved = service.get(meeting.getId());
        assertEquals("GENERATED", saved.status()); assertFalse(saved.canGenerate());
        assertEquals("Manual original", saved.manualContentBeforeAi());
        assertTrue(saved.finalContent().startsWith("Manual original\n\nAI đề xuất:"));
        assertEquals("Summary", saved.aiGeneratedContent().summary()); assertEquals(2, saved.currentMinutesVersion());
        assertEquals("Manual original", ConclusionText.manual(json.readTree(minutes.findByMeetingIdAndVersion(meeting.getId(), 1).orElseThrow().getMinutesJson())));
        assertEquals(saved.finalContent(), ConclusionText.manual(json.readTree(minutes.findByMeetingIdAndVersion(meeting.getId(), 2).orElseThrow().getMinutesJson())));
        assertEquals("AI_CONCLUSION_ALREADY_GENERATED", assertThrows(ConclusionException.class,
            () -> service.generate(meeting.getId(), new Generate(2, "Manual", saved.finalContent()))).code());
    }

    @Test void failedGenerationCanRetryAndKeepsSnapshotAndMinutes() {
        var first = service.generate(meeting.getId(), input()); Work work = service.claim(meeting.getId()).orElseThrow();
        service.fail(work, "CONCLUSION_PROVIDER_FAILED", false);
        assertEquals("FAILED", service.get(meeting.getId()).status()); assertTrue(service.get(meeting.getId()).canGenerate());
        assertEquals(1, minutes.count());
        var retry = service.generate(meeting.getId(), new Generate(1, "New manual", "Manual original"));
        assertNotEquals(first.generationId(), retry.generationId()); assertEquals("New manual", retry.manualContentBeforeAi());
        assertEquals(1, conclusions.count());
    }

    @Test void editsMadeDuringGenerationArePreservedEvenWhenDraftVersionNumberDidNotChange() throws Exception {
        service.generate(meeting.getId(), input()); Work work = service.claim(meeting.getId()).orElseThrow();
        minutesService.updateDraft(meeting.getId(), 1,
            new UpdateMinutesDraftRequest(json.readTree("{\"mainContent\":\"Main edited\",\"conclusion\":\"Manual edited during AI\"}")));
        assertTrue(service.complete(work, receipt(work)));
        View saved = service.get(meeting.getId());
        assertTrue(saved.finalContent().startsWith("Manual edited during AI"));
        assertEquals("Manual original", saved.manualContentBeforeAi());
        assertEquals("Main edited", ConclusionText.main(json.readTree(minutes.findFirstByMeetingIdOrderByVersionDesc(meeting.getId()).orElseThrow().getMinutesJson())));
    }

    @Test void manualVersionEditsSyncFinalWithoutUnlockingAiOrChangingProvenance() throws Exception {
        service.generate(meeting.getId(), input()); Work work = service.claim(meeting.getId()).orElseThrow(); service.complete(work, receipt(work));
        var before = service.get(meeting.getId());
        minutesService.createVersion(meeting.getId(), new CreateMinutesVersionRequest(2,
            json.readTree("{\"mainContent\":\"Main\",\"conclusion\":\"Final edited by human\"}")));
        var after = service.get(meeting.getId());
        assertEquals("Final edited by human", after.finalContent()); assertFalse(after.canGenerate());
        assertEquals(before.aiGeneratedContent(), after.aiGeneratedContent());
        assertEquals(before.manualContentBeforeAi(), after.manualContentBeforeAi());
        assertTrue(after.revision() > before.revision());
    }

    @Test void expiredLeaseCanResumeSameGenerationAndOldLeaseCannotCommit() {
        var initial = service.generate(meeting.getId(), input()); Work old = service.claim(meeting.getId()).orElseThrow();
        conclusions.findByMeetingId(meeting.getId()).orElseThrow().setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        Work recovered = service.claim(meeting.getId()).orElseThrow();
        assertEquals(initial.generationId(), recovered.generationId()); assertNotEquals(old.leaseToken(), recovered.leaseToken());
        assertFalse(service.complete(old, receipt(old))); assertTrue(service.complete(recovered, receipt(recovered)));
    }

    @Test void unsupportedOutputIsNotAppliedToMinutes() {
        service.generate(meeting.getId(), input()); Work work = service.claim(meeting.getId()).orElseThrow();
        var invalid = new Result("Summary", List.of(new Item("Fake", List.of("missing"))), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.complete(work, new ProviderResponse(work.generationId(), invalid)));
        assertEquals(1, minutes.count()); assertNull(conclusions.findByMeetingId(meeting.getId()).orElseThrow().getAiGeneratedContent());
    }

    @Test void permissionsReuseMinutesPolicyAndPublicReadersCannotGenerate() {
        login(account("Outside USER")); assertFalse(service.get(meeting.getId()).canManage());
        assertThrows(MeetingAccessDeniedException.class, () -> service.generate(meeting.getId(), input()));
        meeting.setAccessType(MeetingAccessType.PRIVATE);
        assertThrows(MeetingAccessDeniedException.class, () -> service.get(meeting.getId()));
        login(owner); assertTrue(service.get(meeting.getId()).canManage());
    }

    @Test void preconditionsRejectLiveMeetingsMissingMinutesAndStaleManualWithoutCreatingJobs() {
        meeting.setStatus(MeetingStatus.IN_PROGRESS); meeting.setEndTime(LocalDateTime.now().plusHours(1));
        assertEquals("CONCLUSION_MEETING_NOT_COMPLETED", assertThrows(ConclusionException.class, () -> service.generate(meeting.getId(), input())).code());
        meeting.setStatus(MeetingStatus.COMPLETED);
        assertEquals("CONCLUSION_SOURCE_CHANGED", assertThrows(ConclusionException.class,
            () -> service.generate(meeting.getId(), new Generate(2, "Manual", "Manual original"))).code());
        assertEquals("CONCLUSION_SOURCE_CHANGED", assertThrows(ConclusionException.class,
            () -> service.generate(meeting.getId(), new Generate(1, "Manual", "Stale manual"))).code());
        assertEquals(0, conclusions.count());
    }

    @Test void anonymousQaIsRedactedAndHiddenOrPendingQuestionsAreExcludedFromContext() {
        var participant = new MeetingParticipantEntity(); participant.setMeeting(meeting); participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setFullName("Secret guest"); participant.setEmail("secret@example.test"); participant.setRole(MeetingRole.GUEST);
        participant.setStatus(ParticipantStatus.JOINED); participant.setJoinType(ParticipantJoinType.GUEST); em.persist(participant);
        for (var status : List.of(MeetingQaStatus.OPEN, MeetingQaStatus.PENDING, MeetingQaStatus.HIDDEN)) {
            var q = new MeetingQaQuestionEntity(); q.setMeeting(meeting); q.setParticipant(participant); q.setAnonymous(true);
            q.setClientQuestionId(UUID.randomUUID().toString()); q.setContent("Question " + status); q.setStatus(status); em.persist(q);
        }
        service.generate(meeting.getId(), input()); Work work = service.claim(meeting.getId()).orElseThrow();
        assertEquals(1, work.context().path("qa").size());
        assertEquals("Ẩn danh", work.context().path("qa").get(0).path("author").asText());
        assertFalse(work.context().path("qa").toString().contains("Secret guest"));
        assertFalse(work.context().toString().contains("secret@example.test"));
    }

    UserAccountEntity account(String name) {
        var account = new UserAccountEntity(); account.setPublicId(UUID.randomUUID().toString());
        account.setNormalizedEmail(UUID.randomUUID() + "@conclusion.test"); account.setFullName(name);
        account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(SystemRole.USER); em.persist(account); return account;
    }
    void login(UserAccountEntity account) { principal = new MeetingPrincipal(account.getId(), account.getPublicId(), null,
        account.getNormalizedEmail(), account.getFullName(), null, AccountStatus.ACTIVE, account.getSystemRole(), true, AuthProvider.LOCAL, null); }
}
