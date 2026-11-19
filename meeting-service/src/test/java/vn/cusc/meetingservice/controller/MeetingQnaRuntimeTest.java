package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.client.conclusion.ConclusionClient;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;
import vn.cusc.meetingservice.service.MeetingConclusionDispatcher;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.MeetingCaptureService;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

/** Real HTTP/session/CSRF/Q&A services and PostgreSQL; external LiveKit/capture are mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingQnaRuntimeTest {
    private static final String SCHEMA = "qna_p2_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "Isolated-Qna-Test-2026!";
    private static boolean schemaCreated;
    @LocalServerPort private int port;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockBean private LiveKitService liveKit;
    @MockBean private MeetingCaptureService capture;
    @MockBean private SttClient stt;
    @MockBean private ConclusionClient conclusionClient;
    @Autowired private MeetingConclusionDispatcher conclusionDispatcher;
    private TransactionTemplate tx;
    private MeetingEntity meeting;
    private MeetingEntity otherMeeting;
    private UserAccountEntity owner;
    private UserAccountEntity outsider;
    private MeetingParticipantEntity member;
    private MeetingParticipantEntity secondMember;
    private MeetingParticipantEntity secretary;
    private MeetingParticipantEntity guest;
    private String memberToken;
    private String secondToken;
    private String guestToken;
    private String crossMeetingToken;

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA); schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", MeetingQnaRuntimeTest::username);
        properties.add("spring.datasource.password", MeetingQnaRuntimeTest::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            owner = account(SystemRole.MEETING_MANAGER, "Meeting owner");
            outsider = account(SystemRole.USER, "Uninvited outsider");
            meeting = meeting(owner); otherMeeting = meeting(owner);
            member = participant(meeting, account(SystemRole.USER, "Private member name"), MeetingRole.MEMBER);
            secondMember = participant(meeting, account(SystemRole.USER, "Second private name"), MeetingRole.MEMBER);
            secretary = participant(meeting, account(SystemRole.USER, "Secretary name"), MeetingRole.SECRETARY);
            guest = participant(meeting, null, MeetingRole.GUEST);
            memberToken = connection(member); secondToken = connection(secondMember); guestToken = connection(guest);
            crossMeetingToken = connection(participant(otherMeeting, null, MeetingRole.GUEST));
        });
    }

    @AfterEach void cleanupFixture() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM meetings WHERE id IN (:one,:two)").setParameter("one", meeting.getId()).setParameter("two", otherMeeting.getId()).executeUpdate();
        });
    }

    @AfterAll static void dropOnlyOwnedSchema() throws Exception {
        if (!schemaCreated) return;
        assertTrue(SCHEMA.matches("qna_p2_http_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    private void seedCompletedMinutes() {
        tx.executeWithoutResult(status -> {
            var completed = em.find(MeetingEntity.class, meeting.getId());
            completed.setStatus(MeetingStatus.COMPLETED); completed.setEndTime(LocalDateTime.now().minusMinutes(1));
            var source = new MeetingMinutesEntity(); source.setMeeting(completed); source.setVersion(1);
            source.setStatus(MinutesStatus.DRAFT); source.setSource(MinutesVersionSource.OWNER_EDITED);
            source.setMinutesJson("{\"mainContent\":\"Existing minutes\",\"conclusion\":\"Manual original\"}");
            source.setGeneratedAt(LocalDateTime.now()); em.persist(source);
        });
    }

    @Test void conclusionRealHttpCsrfConcurrentStartAndSuccessOnlyOnceWithVersionedExportContent() throws Exception {
        seedCompletedMinutes();
        String base = "/api/meetings/" + meeting.getId();
        HttpClient first = client(), second = client(), reader = client();
        JsonNode firstCsrf = signIn(first, owner), secondCsrf = signIn(second, owner), readerCsrf = signIn(reader, outsider);
        String payload = json.writeValueAsString(Map.of("expectedMinutesVersion", 1, "manualContent", "Manual original", "expectedStoredConclusion", "Manual original"));
        assertEquals(403, request(first, "POST", base + "/conclusion/generate", payload, null, null).statusCode());
        assertEquals(403, request(reader, "POST", base + "/conclusion/generate", payload, readerCsrf, null).statusCode());
        assertFalse(body(request(reader, "GET", base + "/conclusion", null, null, null), 200).path("canManage").asBoolean());
        when(conclusionClient.generate(any())).thenAnswer(call -> {
            ProviderRequest input = call.getArgument(0);
            assertEquals("Manual original", input.context().path("manual").path("content").asText());
            return new ProviderResponse(input.generationId(), new Result("AI summary", List.of(new Item("Agreed decision", List.of("minutes.v1"))), List.of(), List.of(), List.of()));
        });
        var starts = race(() -> request(first, "POST", base + "/conclusion/generate", payload, firstCsrf, null),
            () -> request(second, "POST", base + "/conclusion/generate", payload, secondCsrf, null));
        String jobId = body(starts.get(0), 202).path("generationId").asText();
        assertEquals(jobId, body(starts.get(1), 202).path("generationId").asText());
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_conclusions WHERE meeting_id=" + meeting.getId()));
        makeConclusionDue();
        conclusionDispatcher.process(meeting.getId()); conclusionDispatcher.process(meeting.getId());
        var workerState = body(request(first, "GET", base + "/conclusion", null, null, null), 200);
        assertEquals("GENERATED", workerState.path("status").asText(), workerState.toString());
        org.mockito.Mockito.verify(conclusionClient, org.mockito.Mockito.times(1)).generate(any());
        var generated = body(request(reader, "GET", base + "/conclusion", null, null, null), 200);
        assertEquals("GENERATED", generated.path("status").asText());
        assertEquals("Manual original", generated.path("manualContentBeforeAi").asText());
        assertTrue(generated.path("finalContent").asText().contains("AI summary"));
        assertFalse(generated.path("canGenerate").asBoolean());
        assertEquals(2, generated.path("currentMinutesVersion").asInt());
        assertCode(request(first, "POST", base + "/conclusion/generate", payload, firstCsrf, null), 409, "AI_CONCLUSION_ALREADY_GENERATED");
        var versions = body(request(first, "GET", base + "/minutes/versions", null, null, null), 200);
        assertTrue(versions.toString().contains("Manual original")); assertTrue(versions.toString().contains("Agreed decision"));
        var rendered = body(request(first, "POST", base + "/minutes/versions/2/render", "{\"templateCode\":\"CUSC_STANDARD\"}", firstCsrf, null), 200);
        assertTrue(rendered.path("html").asText().contains("Manual original"));
        assertTrue(rendered.path("html").asText().contains("Agreed decision"));
        assertFalse(rendered.path("canExportPdf").asBoolean());
        body(request(first, "POST", base + "/minutes/versions/2/finalize", null, firstCsrf, null), 200);
        var pdfRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + base + "/minutes/versions/2/export/pdf"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .header(firstCsrf.path("headerName").asText(), firstCsrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{\"templateCode\":\"CUSC_STANDARD\"}")).build();
        var pdf = first.send(pdfRequest, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode()); assertEquals("application/pdf", pdf.headers().firstValue("Content-Type").orElseThrow());
        try (var document = org.apache.pdfbox.pdmodel.PDDocument.load(pdf.body())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("Manual original")); assertTrue(text.contains("Agreed decision"));
        }
        String edit = json.writeValueAsString(Map.of("baseVersion", 2, "content", Map.of("mainContent", "Updated minutes", "conclusion", "Final human edit")));
        body(request(first, "POST", base + "/minutes/versions", edit, firstCsrf, null), 201);
        var updated = body(request(first, "GET", base + "/conclusion", null, null, null), 200);
        assertEquals("Final human edit", updated.path("finalContent").asText());
        assertEquals("Manual original", updated.path("manualContentBeforeAi").asText());
        assertEquals("AI summary", updated.path("aiGeneratedContent").path("summary").asText());
        assertFalse(updated.path("canGenerate").asBoolean());
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void conclusionFailureCanRetryAndPrivateSecretaryUsesExistingMinutesPermission() throws Exception {
        seedCompletedMinutes();
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meeting.getId()).setAccessType(MeetingAccessType.PRIVATE));
        HttpClient manager = client(), unrelated = client();
        var managerCsrf = signIn(manager, secretary.getUserAccount()); signIn(unrelated, outsider);
        String base = "/api/meetings/" + meeting.getId() + "/conclusion";
        assertEquals(403, request(unrelated, "GET", base, null, null, null).statusCode());
        String payload = json.writeValueAsString(Map.of("expectedMinutesVersion", 1, "expectedStoredConclusion", "Manual original"));
        when(conclusionClient.generate(any())).thenThrow(new ConclusionClient.Failure(false, "CONCLUSION_PROVIDER_FAILED"));
        var initial = body(request(manager, "POST", base + "/generate", payload, managerCsrf, null), 202);
        makeConclusionDue();
        conclusionDispatcher.process(meeting.getId());
        var failure = body(request(manager, "GET", base, null, null, null), 200);
        assertEquals("FAILED", failure.path("status").asText()); assertTrue(failure.path("canGenerate").asBoolean());
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_minutes WHERE meeting_id=" + meeting.getId()));
        var retry = body(request(manager, "POST", base + "/generate", payload, managerCsrf, null), 202);
        assertNotEquals(initial.path("generationId").asText(), retry.path("generationId").asText());
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_conclusions WHERE meeting_id=" + meeting.getId()));
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void defaultContextDistinguishesConnectedParticipantFromTokenlessSessionAndSessionModerator() throws Exception {
        var guestContext = body(request(client(), "GET", qa("/context"), null, null, guestToken), 200);
        assertTrue(guestContext.path("enabled").asBoolean()); assertTrue(guestContext.path("anonymousAllowed").asBoolean());
        assertFalse(guestContext.path("moderationEnabled").asBoolean()); assertTrue(guestContext.path("canManage").asBoolean());
        assertTrue(guestContext.path("canAsk").asBoolean()); assertTrue(guestContext.path("canUpvote").asBoolean());
        HttpClient memberClient = client(); signIn(memberClient, member.getUserAccount());
        var sessionContext = body(request(memberClient, "GET", qa("/context"), null, null, null), 200);
        assertFalse(sessionContext.path("canAsk").asBoolean()); assertFalse(sessionContext.path("canUpvote").asBoolean());
        HttpClient secretaryClient = client(); signIn(secretaryClient, secretary.getUserAccount());
        assertTrue(body(request(secretaryClient, "GET", qa("/context"), null, null, null), 200).path("canManage").asBoolean());
        HttpClient ownerClient = client(); signIn(ownerClient, owner);
        assertTrue(body(request(ownerClient, "GET", qa("/context"), null, null, null), 200).path("canManage").asBoolean());
    }

    @Test void guestTokenAllowsManagementRegardlessOfParticipantRole() throws Exception {
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, guest.getId()).setRole(MeetingRole.CHAIRMAN));
        assertTrue(body(request(client(), "GET", qa("/context"), null, null, guestToken), 200).path("canManage").asBoolean());
    }

    @Test void noCredentialCannotReadButUnrelatedPublicSessionCanViewWithoutAsking() throws Exception {
        assertCode(request(client(), "GET", qa("/context"), null, null, null), 403, "QA_ACCESS_FORBIDDEN");
        HttpClient unrelated = client(); signIn(unrelated, outsider);
        body(request(unrelated, "GET", qa("/questions"), null, null, null), 200);
        var context = body(request(unrelated, "GET", qa("/context"), null, null, null), 200);
        assertFalse(context.path("canAsk").asBoolean());
        assertFalse(context.path("canManage").asBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MEETING_MANAGER", "ADMIN"})
    void openVisibilityUsesRosterForPrivateMeetingsAndAllowsPublicReads(SystemRole systemRole) throws Exception {
        UserAccountEntity viewer = tx.execute(status -> account(systemRole, "Visibility viewer"));
        tx.executeWithoutResult(status -> {
            var privateMeeting = em.find(MeetingEntity.class, meeting.getId());
            privateMeeting.setAccessType(MeetingAccessType.PRIVATE);
            privateMeeting.setEndTime(LocalDateTime.now().minusMinutes(1));
            em.find(MeetingEntity.class, otherMeeting.getId()).setEndTime(LocalDateTime.now().minusMinutes(1));
        });
        HttpClient signed = client(); signIn(signed, viewer);
        String privateBase = "/api/meetings/" + meeting.getId();
        String publicBase = "/api/meetings/" + otherMeeting.getId();
        var before = body(request(signed, "GET", "/api/meetings?size=1", null, null, null), 200);
        assertEquals(1, before.path("totalElements").asInt());
        assertEquals(List.of(otherMeeting.getId()), ids(before));
        assertEquals(1, body(request(signed, "GET", "/api/minutes/summary", null, null, null), 200)
            .path("totalMeetings").asInt());
        for (String suffix : List.of("", "/participants", "/documents", "/minutes/versions",
            "/minutes/transcript", "/polls", "/qa/context", "/qa/questions")) {
            body(request(signed, "GET", publicBase + suffix, null, null, null), 200);
            assertEquals(403, request(signed, "GET", privateBase + suffix, null, null, null).statusCode(), suffix);
        }
        var invitation = tx.execute(status -> participant(em.find(MeetingEntity.class, meeting.getId()),
            em.find(UserAccountEntity.class, viewer.getId()), MeetingRole.MEMBER));
        var invited = body(request(signed, "GET", "/api/meetings?size=1", null, null, null), 200);
        assertEquals(2, invited.path("totalElements").asInt());
        var secondPage = body(request(signed, "GET", "/api/meetings?size=1&page=1", null, null, null), 200);
        var allIds = new HashSet<>(ids(invited)); allIds.addAll(ids(secondPage));
        assertEquals(Set.of(meeting.getId(), otherMeeting.getId()), allIds);
        assertEquals(2, body(request(signed, "GET", "/api/minutes/summary", null, null, null), 200)
            .path("totalMeetings").asInt());
        for (String suffix : List.of("", "/participants", "/documents", "/minutes/versions",
            "/minutes/transcript", "/polls", "/qa/context")) {
            body(request(signed, "GET", privateBase + suffix, null, null, null), 200);
        }
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, invitation.getId())
            .setStatus(ParticipantStatus.REMOVED));
        assertEquals(1, body(request(signed, "GET", "/api/meetings", null, null, null), 200)
            .path("totalElements").asInt());
        assertEquals(403, request(signed, "GET", privateBase, null, null, null).statusCode());
        assertEquals(403, request(signed, "GET", privateBase + "/qa/context", null, null, null).statusCode());
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void emailOnlyPrivateInvitationIsVisibleBeforeJoiningAndRemovedInvitationIsDenied() throws Exception {
        var invitation = tx.execute(status -> {
            var privateMeeting = em.find(MeetingEntity.class, meeting.getId());
            privateMeeting.setAccessType(MeetingAccessType.PRIVATE);
            var invited = participant(privateMeeting, null, MeetingRole.MEMBER);
            invited.setInvitedEmailNormalized(outsider.getNormalizedEmail());
            invited.setStatus(ParticipantStatus.INVITED);
            return invited;
        });
        HttpClient signed = client(); signIn(signed, outsider);
        assertEquals(2, body(request(signed, "GET", "/api/meetings", null, null, null), 200)
            .path("totalElements").asInt());
        body(request(signed, "GET", "/api/meetings/" + meeting.getId(), null, null, null), 200);
        body(request(signed, "GET", qa("/context"), null, null, null), 200);
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, invitation.getId())
            .setStatus(ParticipantStatus.REMOVED));
        assertEquals(1, body(request(signed, "GET", "/api/meetings", null, null, null), 200)
            .path("totalElements").asInt());
        assertEquals(403, request(signed, "GET", "/api/meetings/" + meeting.getId(), null, null, null).statusCode());
    }

    @Test void invalidSuppliedTokenNeverFallsBackToOwnerSessionAndCrossMeetingTokenIsRejected() throws Exception {
        HttpClient ownerClient = client(); signIn(ownerClient, owner);
        assertCode(request(ownerClient, "GET", qa("/context"), null, null, "invalid"), 403, "QA_INVALID_PARTICIPANT_TOKEN");
        assertCode(request(ownerClient, "GET", qa("/context"), null, null, ""), 403, "QA_INVALID_PARTICIPANT_TOKEN");
        assertCode(request(client(), "GET", qa("/context"), null, null, crossMeetingToken), 403, "QA_INVALID_PARTICIPANT_TOKEN");
    }

    @Test void signedInMemberCannotUseAnotherAccountOrGuestToken() throws Exception {
        HttpClient signed = client(); signIn(signed, member.getUserAccount());
        assertCode(request(signed, "GET", qa("/context"), null, null, secondToken), 403, "QA_PARTICIPANT_IDENTITY_MISMATCH");
        assertCode(request(signed, "GET", qa("/questions"), null, null, guestToken), 403, "QA_PARTICIPANT_IDENTITY_MISMATCH");
        assertEquals(200, request(signed, "GET", qa("/context"), null, null, memberToken).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"LEFT", "REMOVED"})
    void disconnectedConnectionCannotReadOrSubmitUsingOldToken(String state) throws Exception {
        connectionState(guestToken, state);
        HttpClient guestClient = client(); JsonNode csrf = csrf(guestClient);
        assertCode(request(guestClient, "GET", qa("/questions"), null, null, guestToken), 403, "QA_CONNECTION_NOT_CONNECTED");
        assertCode(request(guestClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Question", true), csrf, guestToken), 403, "QA_CONNECTION_NOT_CONNECTED");
    }

    @ParameterizedTest @ValueSource(strings = {"CHAIRMAN", "SECRETARY", "MEMBER", "GUEST"})
    void realJoinIssuedPendingTokenCanAskBeforeWebhookForEveryMeetingRole(String roleName) throws Exception {
        var role = MeetingRole.valueOf(roleName);
        var candidate = role == MeetingRole.SECRETARY ? secretary : member;
        tx.executeWithoutResult(status -> {
            em.find(MeetingEntity.class, meeting.getId()).setAccessType(MeetingAccessType.PUBLIC);
            if (role != MeetingRole.GUEST) em.find(MeetingParticipantEntity.class, candidate.getId()).setRole(role);
        });
        when(liveKit.ensureRoomExists(meeting.getRoomName())).thenReturn(
            new LiveKitRoomResponse(meeting.getRoomName(), "RM_QA_PENDING_TEST", 0, 0));
        when(liveKit.generateJoinToken(anyString(), anyString(), anyString(), anyMap(), eq(true), eq(true)))
            .thenReturn("isolated-livekit-join-token");
        HttpClient caller = client();
        JsonNode csrf = role == MeetingRole.GUEST ? csrf(caller) : signIn(caller, candidate.getUserAccount());
        String joinInput = "{\"participantType\":\"" + (role == MeetingRole.GUEST ? "GUEST" : "AUTHENTICATED")
            + "\",\"displayName\":\"Pending Q&A participant\"}";
        var joined = body(request(caller, "POST", "/api/meetings/" + meeting.getId() + "/join", joinInput, csrf, null), 200);
        String token = joined.path("participant").path("chatWriteToken").asText();
        assertFalse(token.isBlank());
        assertEquals(roleName, joined.path("participant").path("role").asText());
        assertEquals("PENDING", tx.execute(status -> em.createQuery(
            "select c.status from MeetingParticipantConnectionEntity c where c.meeting.id=:meeting and c.chatTokenHash=:hash",
            ParticipantConnectionStatus.class).setParameter("meeting", meeting.getId())
            .setParameter("hash", MeetingChatTokenUtils.sha256(token)).getSingleResult().name()));
        clearInvocations(liveKit, capture, stt);

        var context = body(request(caller, "GET", qa("/context"), null, null, token), 200);
        assertTrue(context.path("canAsk").asBoolean());
        assertTrue(context.path("canUpvote").asBoolean());
        boolean manager = true;
        assertEquals(manager, context.path("canManage").asBoolean());
        String input = questionJson(UUID.randomUUID().toString(), "Question without webhook", false);
        assertEquals(403, request(caller, "POST", qa("/questions"), input, null, token).statusCode());
        assertEquals(0, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        var first = body(request(caller, "POST", qa("/questions"), input, csrf, token), 201);
        assertEquals("Question without webhook", first.path("content").asText());
        assertTrue(first.path("mine").asBoolean());
        assertEquals(first, body(request(caller, "POST", qa("/questions"), input, csrf, token), 201));
        var vote = body(request(caller, "PUT", qa("/questions/" + first.path("id").asLong() + "/upvote"),
            "{\"upvoted\":true}", csrf, token), 200);
        assertEquals(1, vote.path("upvoteCount").asInt());
        if (!manager) {
            assertCode(request(caller, "POST", qa("/questions/" + first.path("id").asLong() + "/actions"),
                "{\"action\":\"HIDE\",\"expectedVersion\":1}", csrf, token), 403, "QA_MODERATION_FORBIDDEN");
        }
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_participant_connections WHERE meeting_id=" + meeting.getId()
            + " AND chat_token_hash='" + MeetingChatTokenUtils.sha256(token) + "' AND status='PENDING' AND joined_at IS NULL"));
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void removedParticipantAndDisabledAccountBearerTokenAreRejected() throws Exception {
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, guest.getId()).setStatus(ParticipantStatus.REMOVED));
        assertCode(request(client(), "GET", qa("/context"), null, null, guestToken), 403, "QA_PARTICIPANT_REMOVED");
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, member.getUserAccount().getId()).setAccountStatus(AccountStatus.DISABLED));
        assertCode(request(client(), "GET", qa("/context"), null, null, memberToken), 403, "QA_ACCOUNT_DISABLED");
    }

    @Test void evenSessionAuthenticatedMembersNeedConnectionTokenForAskAndUpvote() throws Exception {
        long id = seedQuestion(secondMember, "Public question", MeetingQaStatus.OPEN, false, 0).getId();
        HttpClient signed = client(); JsonNode csrf = signIn(signed, member.getUserAccount());
        assertCode(request(signed, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "New question", false), csrf, null), 403, "QA_PARTICIPANT_TOKEN_REQUIRED");
        assertCode(request(signed, "PUT", qa("/questions/" + id + "/upvote"), "{\"upvoted\":true}", csrf, null), 403, "QA_PARTICIPANT_TOKEN_REQUIRED");
    }

    @Test void realCsrfProtectionStillAppliesToGuestAskAndUpvote() throws Exception {
        long id = seedQuestion(member, "Public question", MeetingQaStatus.OPEN, false, 0).getId();
        HttpClient guestClient = client();
        assertEquals(403, request(guestClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Missing CSRF", true), null, guestToken).statusCode());
        assertEquals(403, request(guestClient, "PUT", qa("/questions/" + id + "/upvote"), "{\"upvoted\":true}", null, guestToken).statusCode());
        assertEquals(0, nativeCount("SELECT count(*) FROM meeting_qa_upvotes WHERE question_id=" + id));
        assertEquals(200, request(guestClient, "PUT", qa("/questions/" + id + "/upvote"), "{\"upvoted\":true}", csrf(guestClient), guestToken).statusCode());
    }

    @Test void anonymousSubmissionIsPersistedAndAnonymousForAuthorOtherMembersAndModerator() throws Exception {
        HttpClient guestClient = client(); String retry = UUID.randomUUID().toString();
        JsonNode receipt = body(request(guestClient, "POST", qa("/questions"), questionJson(retry, "  Anonymous question  ", true), csrf(guestClient), guestToken), 201);
        assertEquals("Anonymous question", receipt.path("content").asText()); assertTrue(receipt.path("mine").asBoolean());
        assertEquals(retry, receipt.path("clientQuestionId").asText()); assertAnonymous(receipt);
        long id = receipt.path("id").asLong();
        tx.executeWithoutResult(status -> {
            var stored = em.find(MeetingQaQuestionEntity.class, id);
            assertEquals(guest.getId(), stored.getParticipant().getId()); assertNull(stored.getAuthorDisplayName());
        });
        HttpClient memberClient = client(); signIn(memberClient, member.getUserAccount());
        JsonNode viewed = body(request(memberClient, "GET", qa("/questions"), null, null, null), 200).path("content").get(0);
        assertAnonymous(viewed); assertFalse(viewed.path("mine").asBoolean()); assertTrue(viewed.path("clientQuestionId").isNull());
        HttpClient moderator = client(); signIn(moderator, owner);
        assertAnonymous(body(request(moderator, "GET", qa("/questions"), null, null, null), 200).path("content").get(0));
    }

    @Test void identicalSubmissionRetryIsStableAndChangedContentOrAnonymousConflicts() throws Exception {
        HttpClient guestClient = client(); JsonNode csrf = csrf(guestClient); String retry = UUID.randomUUID().toString();
        JsonNode first = body(request(guestClient, "POST", qa("/questions"), questionJson(retry, "Question", true), csrf, guestToken), 201);
        JsonNode again = body(request(guestClient, "POST", qa("/questions"), questionJson(retry, "  Question  ", true), csrf, guestToken), 201);
        assertEquals(first, again);
        assertCode(request(guestClient, "POST", qa("/questions"), questionJson(retry, "Changed question", true), csrf, guestToken), 409, "QA_CONFLICT");
        assertCode(request(guestClient, "POST", qa("/questions"), questionJson(retry, "Question", false), csrf, guestToken), 409, "QA_CONFLICT");
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        HttpClient another = client();
        assertNotEquals(first.path("id"), body(request(another, "POST", qa("/questions"), questionJson(retry, "Question", true), csrf(another), secondToken), 201).path("id"));
    }

    @Test void desiredUpvoteIsIdempotentAndReconnectRestoresParticipantOwnershipAndVote() throws Exception {
        var question = seedQuestion(member, "Question", MeetingQaStatus.OPEN, false, 0);
        HttpClient signed = client(); JsonNode csrf = signIn(signed, member.getUserAccount());
        String route = qa("/questions/" + question.getId() + "/upvote");
        JsonNode one = body(request(signed, "PUT", route, "{\"upvoted\":true}", csrf, memberToken), 200);
        JsonNode repeated = body(request(signed, "PUT", route, "{\"upvoted\":true}", csrf, memberToken), 200);
        assertEquals(1, one.path("upvoteCount").asInt()); assertTrue(one.path("upvotedByMe").asBoolean());
        assertEquals(one, repeated); assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_upvotes WHERE question_id=" + question.getId()));
        connectionState(memberToken, "LEFT");
        String reconnectToken = tx.execute(status -> connection(em.find(MeetingParticipantEntity.class, member.getId())));
        HttpClient reconnect = client(); JsonNode reconnectCsrf = signIn(reconnect, member.getUserAccount());
        JsonNode restored = body(request(reconnect, "GET", qa("/questions?filter=MINE"), null, null, reconnectToken), 200).path("content").get(0);
        assertTrue(restored.path("mine").asBoolean()); assertTrue(restored.path("upvotedByMe").asBoolean());
        assertCode(request(reconnect, "GET", qa("/questions"), null, null, memberToken), 403, "QA_CONNECTION_NOT_CONNECTED");
        JsonNode removed = body(request(reconnect, "PUT", route, "{\"upvoted\":false}", reconnectCsrf, reconnectToken), 200);
        assertEquals(0, removed.path("upvoteCount").asInt()); assertFalse(removed.path("upvotedByMe").asBoolean());
        assertEquals(removed.path("version"), body(request(reconnect, "PUT", route, "{\"upvoted\":false}", reconnectCsrf, reconnectToken), 200).path("version"));
    }

    @Test void filteringPrecedesPaginationAndTotalsAndPrivateQuestionIdsCannotBeUpvoted() throws Exception {
        var open = seedQuestion(secondMember, "Public open", MeetingQaStatus.OPEN, false, 0);
        var answered = seedQuestion(secondMember, "Public answered", MeetingQaStatus.ANSWERED, false, 0);
        var ownPending = seedQuestion(member, "My pending", MeetingQaStatus.PENDING, true, 0);
        var otherPending = seedQuestion(secondMember, "Other pending", MeetingQaStatus.PENDING, true, 0);
        var hidden = seedQuestion(member, "My hidden", MeetingQaStatus.HIDDEN, true, 0);
        var deleted = seedQuestion(secondMember, "Deleted", MeetingQaStatus.DELETED, true, 0);
        seedQuestion(participantInOtherMeeting(), "Another meeting", MeetingQaStatus.OPEN, true, 0);
        HttpClient signed = client(); JsonNode csrf = signIn(signed, member.getUserAccount());
        var first = body(request(signed, "GET", qa("/questions?size=2&page=0&sort=OLDEST"), null, null, memberToken), 200);
        assertEquals(6, first.path("totalElements").asInt()); assertEquals(3, first.path("totalPages").asInt()); assertEquals(2, first.path("content").size());
        var second = body(request(signed, "GET", qa("/questions?size=2&page=1&sort=OLDEST"), null, null, memberToken), 200);
        assertEquals(2, second.path("content").size());
        Set<Long> ids = new HashSet<>(); first.path("content").forEach(q -> ids.add(q.path("id").asLong())); second.path("content").forEach(q -> ids.add(q.path("id").asLong()));
        assertEquals(Set.of(open.getId(), answered.getId(), ownPending.getId(), otherPending.getId()), ids);
        assertEquals(2, body(request(signed, "GET", qa("/questions?filter=MINE"), null, null, memberToken), 200).path("totalElements").asInt());
        assertEquals(2, body(request(signed, "GET", qa("/questions?filter=PENDING"), null, null, memberToken), 200).path("totalElements").asInt());
        HttpClient outsideReader = client(); signIn(outsideReader, outsider);
        assertEquals(2, body(request(outsideReader, "GET", qa("/questions?size=1"), null, null, null), 200).path("totalElements").asInt());
        assertCode(request(outsideReader, "GET", qa("/questions?filter=PENDING"), null, null, null), 403, "QA_MODERATION_FORBIDDEN");
        for (var privateQuestion : List.of(otherPending, ownPending, hidden, deleted)) {
            assertCode(request(signed, "PUT", qa("/questions/" + privateQuestion.getId() + "/upvote"), "{\"upvoted\":true}", csrf, memberToken), 404, "QA_QUESTION_NOT_FOUND");
        }
    }

    @Test void stableTimeAndPopularitySortsUseIdTieBreakerAndKeepPagesDistinct() throws Exception {
        LocalDateTime sameTime = LocalDateTime.of(2026, 10, 6, 8, 0);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var q = seedQuestion(member, "Question " + i, MeetingQaStatus.OPEN, false, i == 1 ? 2 : 0);
            ids.add(q.getId()); tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE meeting_qa_questions SET created_at=:time WHERE id=:id").setParameter("time", sameTime).setParameter("id", q.getId()).executeUpdate());
        }
        assertEquals(ids, ids(body(request(client(), "GET", qa("/questions?sort=OLDEST"), null, null, memberToken), 200)));
        assertEquals(List.of(ids.get(2), ids.get(1), ids.get(0)), ids(body(request(client(), "GET", qa("/questions?sort=NEWEST"), null, null, memberToken), 200)));
        assertEquals(List.of(ids.get(1), ids.get(2), ids.get(0)), ids(body(request(client(), "GET", qa("/questions?sort=POPULAR"), null, null, memberToken), 200)));
        assertNotEquals(ids(body(request(client(), "GET", qa("/questions?size=1&page=0&sort=NEWEST"), null, null, memberToken), 200)),
            ids(body(request(client(), "GET", qa("/questions?size=1&page=1&sort=NEWEST"), null, null, memberToken), 200)));
    }

    @ParameterizedTest @ValueSource(strings = {"?size=0", "?size=51", "?page=-1", "?size=50&page=2147483647", "?sort=UNKNOWN", "?filter=UNKNOWN"})
    void rejectsInvalidPaginationOrEnumsAsClientErrors(String query) throws Exception {
        assertEquals(400, request(client(), "GET", qa("/questions" + query), null, null, guestToken).statusCode());
    }

    @Test void rejectsMalformedUuidEmptyOrOversizedQuestionsAndNullVote() throws Exception {
        HttpClient guestClient = client(); JsonNode csrf = csrf(guestClient);
        for (String invalid : List.of("not-a-uuid", "1-1-1-1-1")) {
            assertEquals(400, request(guestClient, "POST", qa("/questions"), questionJson(invalid, "Question", true), csrf, guestToken).statusCode());
        }
        for (String content : List.of("   ", "x".repeat(2001))) {
            assertEquals(400, request(guestClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), content, true), csrf, guestToken).statusCode());
        }
        assertEquals(400, request(guestClient, "PUT", qa("/questions/999/upvote"), "{\"upvoted\":null}", csrf, guestToken).statusCode());
        assertEquals(0, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
    }

    @Test void currentSettingsAndMeetingStatusBlockNewWritesWithoutRemovingHistory() throws Exception {
        var question = seedQuestion(member, "Visible history", MeetingQaStatus.OPEN, false, 0);
        tx.executeWithoutResult(status -> { var settings = new MeetingQaSettingsEntity(); settings.setMeeting(em.find(MeetingEntity.class, meeting.getId())); settings.setEnabled(false); em.persist(settings); });
        HttpClient guestClient = client(); JsonNode csrf = csrf(guestClient);
        assertFalse(body(request(guestClient, "GET", qa("/context"), null, null, guestToken), 200).path("canAsk").asBoolean());
        assertCode(request(guestClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Question", true), csrf, guestToken), 409, "QA_CONFLICT");
        assertCode(request(guestClient, "PUT", qa("/questions/" + question.getId() + "/upvote"), "{\"upvoted\":true}", csrf, guestToken), 409, "QA_CONFLICT");
        assertEquals(1, body(request(guestClient, "GET", qa("/questions"), null, null, guestToken), 200).path("totalElements").asInt());
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meeting.getId()).setStatus(MeetingStatus.COMPLETED));
        HttpClient memberClient = client(); signIn(memberClient, member.getUserAccount());
        assertEquals(1, body(request(memberClient, "GET", qa("/questions"), null, null, null), 200).path("totalElements").asInt());
    }

    @Test void concurrentSameRetryReceivesOnePersistedQuestionAndConcurrentDesiredUpvotesOneVote() throws Exception {
        HttpClient first = client(); HttpClient second = client(); JsonNode firstCsrf = csrf(first); JsonNode secondCsrf = csrf(second);
        String payload = questionJson(UUID.randomUUID().toString(), "Race question", true);
        var asks = race(() -> request(first, "POST", qa("/questions"), payload, firstCsrf, guestToken),
            () -> request(second, "POST", qa("/questions"), payload, secondCsrf, guestToken));
        JsonNode one = body(asks.get(0), 201); JsonNode two = body(asks.get(1), 201); assertEquals(one.path("id"), two.path("id"));
        long id = one.path("id").asLong(); assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        var votes = race(() -> request(first, "PUT", qa("/questions/" + id + "/upvote"), "{\"upvoted\":true}", firstCsrf, guestToken),
            () -> request(second, "PUT", qa("/questions/" + id + "/upvote"), "{\"upvoted\":true}", secondCsrf, guestToken));
        for (var vote : votes) assertEquals(1, body(vote, 200).path("upvoteCount").asInt());
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_upvotes WHERE question_id=" + id));
        assertEquals(1, nativeCount("SELECT upvote_count FROM meeting_qa_questions WHERE id=" + id));
    }

    @ParameterizedTest @ValueSource(strings = {"MEMBER", "OWNER", "CHAIRMAN", "SECRETARY"})
    void signedInModeratorRolesCanChangeSettingsAndActionsWithoutRoomToken(String role) throws Exception {
        UserAccountEntity moderator = moderator(role);
        HttpClient moderatorClient = client(); JsonNode csrf = signIn(moderatorClient, moderator);
        JsonNode settings = body(request(moderatorClient, "PUT", qa("/settings"), settingsJson(true, true, true, 0), csrf, null), 200);
        assertTrue(settings.path("moderationEnabled").asBoolean()); assertTrue(settings.path("canManage").asBoolean());
        assertFalse(settings.path("canAsk").asBoolean());
        var pending = seedQuestion(member, "Anonymous pending", MeetingQaStatus.PENDING, true, 0);
        JsonNode approved = body(request(moderatorClient, "POST", qa("/questions/" + pending.getId() + "/actions"), actionJson("APPROVE", 0), csrf, null), 200);
        assertEquals("OPEN", approved.path("status").asText()); assertAnonymous(approved);
        assertEquals(!role.equals("MEMBER"), approved.path("clientQuestionId").isNull());
        assertEquals(role.equals("MEMBER"), approved.path("mine").asBoolean());
    }

    @Test void writtenAnswersRoundTripAcrossParticipantsAndHistoryAndRejectConflictingWrites() throws Exception {
        var question = seedQuestion(guest, "Anonymous question", MeetingQaStatus.OPEN, true, 0);
        HttpClient responder = client(); JsonNode responderCsrf = signIn(responder, member.getUserAccount());
        HttpClient guestReader = client(); JsonNode guestCsrf = csrf(guestReader);
        String endpoint = qa("/questions/" + question.getId() + "/actions");
        String payload = json.writeValueAsString(Map.of("action", "ANSWER", "expectedVersion", 0,
            "answer", "  Tiếng Việt, \"trả lời\"\nDòng hai  "));
        assertEquals(403, request(responder, "POST", endpoint, payload, null, memberToken).statusCode());
        JsonNode saved = body(request(responder, "POST", endpoint, payload, responderCsrf, memberToken), 200);
        assertEquals("Tiếng Việt, \"trả lời\"\nDòng hai", saved.path("answer").asText());
        assertEquals(member.getFullName(), saved.path("answerAuthorDisplayName").asText());
        assertAnonymous(saved);
        JsonNode reloaded = body(request(guestReader, "GET", qa("/questions"), null, null, guestToken), 200).path("content").get(0);
        assertEquals(saved.path("answer"), reloaded.path("answer"));
        assertEquals(saved.path("answerAuthorDisplayName"), reloaded.path("answerAuthorDisplayName"));
        assertCode(request(guestReader, "POST", endpoint,
            json.writeValueAsString(Map.of("action", "ANSWER", "expectedVersion", 0, "answer", "Stale")), guestCsrf, guestToken), 409, "QA_CONFLICT");
        var reportRows = csvRows(report(responder, memberToken));
        assertEquals(saved.path("answer").asText(), reportRows.get(1).get(6));
        assertEquals(member.getFullName(), reportRows.get(1).get(7));
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meeting.getId()).setStatus(MeetingStatus.COMPLETED));
        assertEquals(saved.path("answer"), body(request(guestReader, "GET", qa("/questions"), null, null, guestToken), 200).path("content").get(0).path("answer"));
        assertCode(request(responder, "POST", endpoint,
            json.writeValueAsString(Map.of("action", "ANSWER", "expectedVersion", 1, "answer", "After end")), responderCsrf, memberToken), 409, "QA_CONFLICT");
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void memberAndGuestCanManageButInvalidTokenDoesNotFallback() throws Exception {
        var question = seedQuestion(member, "Public question", MeetingQaStatus.OPEN, true, 0);
        HttpClient memberClient = client(); JsonNode memberCsrf = signIn(memberClient, member.getUserAccount());
        HttpClient guestClient = client(); JsonNode guestCsrf = csrf(guestClient);
        body(request(memberClient, "PUT", qa("/settings"), settingsJson(false, false, true, 0), memberCsrf, memberToken), 200);
        body(request(memberClient, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("ANSWER", 0), memberCsrf, memberToken), 200);
        body(request(guestClient, "PUT", qa("/settings"), settingsJson(true, true, false, 1), guestCsrf, guestToken), 200);
        body(request(guestClient, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("HIDE", 1), guestCsrf, guestToken), 200);
        HttpClient ownerClient = client(); JsonNode ownerCsrf = signIn(ownerClient, owner);
        assertCode(request(ownerClient, "PUT", qa("/settings"), settingsJson(false, true, false, 2), ownerCsrf, "invalid"), 403, "QA_INVALID_PARTICIPANT_TOKEN");
        assertEquals(2, nativeCount("SELECT version FROM meeting_qa_questions WHERE id=" + question.getId()));
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=" + meeting.getId()));
    }

    @Test void moderatorMutationsStillRequireCsrfAndValidatedVersionAndAction() throws Exception {
        var question = seedQuestion(member, "Public question", MeetingQaStatus.OPEN, true, 0);
        HttpClient moderatorClient = client(); JsonNode csrf = signIn(moderatorClient, owner);
        assertEquals(403, request(moderatorClient, "PUT", qa("/settings"), settingsJson(false, true, false, 0), null, null).statusCode());
        assertEquals(403, request(moderatorClient, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("ANSWER", 0), null, null).statusCode());
        assertEquals(400, request(moderatorClient, "PUT", qa("/settings"), "{\"enabled\":true,\"anonymousAllowed\":true,\"moderationEnabled\":true}", csrf, null).statusCode());
        assertEquals(400, request(moderatorClient, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("UNKNOWN", 0), csrf, null).statusCode());
        assertEquals(400, request(moderatorClient, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("ANSWER", -1), csrf, null).statusCode());
        assertEquals(0, nativeCount("SELECT version FROM meeting_qa_questions WHERE id=" + question.getId()));
    }

    @Test void approveAnswerHideAndDeletePreserveAnonymousPrivacyAndRejectStaleVersions() throws Exception {
        HttpClient moderatorClient = client(); JsonNode moderatorCsrf = signIn(moderatorClient, owner);
        body(request(moderatorClient, "PUT", qa("/settings"), settingsJson(true, true, true, 0), moderatorCsrf, null), 200);
        HttpClient authorClient = client(); JsonNode authorCsrf = csrf(authorClient); String retry = UUID.randomUUID().toString();
        JsonNode pending = body(request(authorClient, "POST", qa("/questions"), questionJson(retry, "Private pending question", true), authorCsrf, guestToken), 201);
        assertEquals("PENDING", pending.path("status").asText()); assertAnonymous(pending);
        long id = pending.path("id").asLong();
        HttpClient reader = client(); signIn(reader, member.getUserAccount());
        assertEquals(1, body(request(reader, "GET", qa("/questions"), null, null, null), 200).path("totalElements").asInt());
        assertEquals(1, body(request(authorClient, "GET", qa("/questions"), null, null, guestToken), 200).path("totalElements").asInt());
        assertAnonymous(body(request(moderatorClient, "GET", qa("/questions?filter=PENDING"), null, null, null), 200).path("content").get(0));
        JsonNode approved = body(request(moderatorClient, "POST", qa("/questions/" + id + "/actions"), actionJson("APPROVE", pending.path("version").asLong()), moderatorCsrf, null), 200);
        assertEquals("OPEN", approved.path("status").asText()); assertAnonymous(approved);
        assertEquals(1, body(request(reader, "GET", qa("/questions"), null, null, null), 200).path("totalElements").asInt());
        assertCode(request(moderatorClient, "POST", qa("/questions/" + id + "/actions"), actionJson("ANSWER", pending.path("version").asLong()), moderatorCsrf, null), 409, "QA_CONFLICT");
        JsonNode answered = body(request(moderatorClient, "POST", qa("/questions/" + id + "/actions"), actionJson("ANSWER", approved.path("version").asLong()), moderatorCsrf, null), 200);
        assertEquals("ANSWERED", answered.path("status").asText()); assertFalse(answered.path("answeredAt").isNull()); assertAnonymous(answered);
        JsonNode hidden = body(request(moderatorClient, "POST", qa("/questions/" + id + "/actions"), actionJson("HIDE", answered.path("version").asLong()), moderatorCsrf, null), 200);
        assertEquals("HIDDEN", hidden.path("status").asText()); assertAnonymous(hidden);
        assertEquals(1, body(request(reader, "GET", qa("/questions"), null, null, null), 200).path("totalElements").asInt());
        assertEquals(1, body(request(authorClient, "GET", qa("/questions?filter=MINE"), null, null, guestToken), 200).path("totalElements").asInt());
        assertEquals("HIDDEN", body(request(authorClient, "POST", qa("/questions"), questionJson(retry, "Private pending question", true), authorCsrf, guestToken), 201).path("status").asText());
        JsonNode deleted = body(request(moderatorClient, "POST", qa("/questions/" + id + "/actions"), actionJson("DELETE", hidden.path("version").asLong()), moderatorCsrf, null), 200);
        assertEquals("DELETED", deleted.path("status").asText()); assertAnonymous(deleted);
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE id=" + id + " AND status='DELETED'"));
        JsonNode moderatorView = body(request(moderatorClient, "GET", qa("/questions"), null, null, null), 200).path("content").get(0);
        assertEquals("DELETED", moderatorView.path("status").asText()); assertAnonymous(moderatorView);
    }

    @Test void settingsChangesKeepOldPendingAndAnonymousQuestionsAndRequireCurrentVersion() throws Exception {
        HttpClient moderatorClient = client(); JsonNode moderatorCsrf = signIn(moderatorClient, owner);
        JsonNode moderated = body(request(moderatorClient, "PUT", qa("/settings"), settingsJson(true, true, true, 0), moderatorCsrf, null), 200);
        HttpClient authorClient = client(); JsonNode authorCsrf = csrf(authorClient);
        JsonNode pending = body(request(authorClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Old anonymous pending", true), authorCsrf, guestToken), 201);
        JsonNode unmoderated = body(request(moderatorClient, "PUT", qa("/settings"), settingsJson(true, false, false, moderated.path("settingsVersion").asLong()), moderatorCsrf, null), 200);
        assertFalse(unmoderated.path("moderationEnabled").asBoolean()); assertFalse(unmoderated.path("anonymousAllowed").asBoolean());
        assertCode(request(moderatorClient, "PUT", qa("/settings"), settingsJson(false, false, false, moderated.path("settingsVersion").asLong()), moderatorCsrf, null), 409, "QA_CONFLICT");
        JsonNode old = body(request(authorClient, "GET", qa("/questions?filter=MINE"), null, null, guestToken), 200).path("content").get(0);
        assertEquals("PENDING", old.path("status").asText()); assertAnonymous(old);
        assertCode(request(authorClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "New anonymous blocked", true), authorCsrf, guestToken), 409, "QA_CONFLICT");
        JsonNode named = body(request(authorClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "New named open", false), authorCsrf, guestToken), 201);
        assertEquals("OPEN", named.path("status").asText()); assertEquals(guest.getFullName(), named.path("authorDisplayName").asText());
        assertEquals("PENDING", tx.execute(status -> em.find(MeetingQaQuestionEntity.class, pending.path("id").asLong()).getStatus().name()));
        JsonNode disabled = body(request(moderatorClient, "PUT", qa("/settings"), settingsJson(false, false, false, unmoderated.path("settingsVersion").asLong()), moderatorCsrf, null), 200);
        assertFalse(disabled.path("enabled").asBoolean());
        assertCode(request(authorClient, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Blocked new question", false), authorCsrf, guestToken), 409, "QA_CONFLICT");
        assertCode(request(authorClient, "PUT", qa("/questions/" + named.path("id").asLong() + "/upvote"), "{\"upvoted\":true}", authorCsrf, guestToken), 409, "QA_CONFLICT");
        assertEquals(2, body(request(authorClient, "GET", qa("/questions"), null, null, guestToken), 200).path("totalElements").asInt());
    }

    @ParameterizedTest @ValueSource(strings = {"COMPLETED", "CANCELLED"})
    void terminalMeetingRefusesSettingsActionsNewQuestionsAndUpvotes(String state) throws Exception {
        var question = seedQuestion(member, "Existing anonymous question", MeetingQaStatus.OPEN, true, 0);
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meeting.getId()).setStatus(MeetingStatus.valueOf(state)));
        HttpClient moderator = client(); JsonNode moderatorCsrf = signIn(moderator, owner);
        assertCode(request(moderator, "PUT", qa("/settings"), settingsJson(false, true, false, 0), moderatorCsrf, null), 409, "QA_CONFLICT");
        assertCode(request(moderator, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("ANSWER", 0), moderatorCsrf, null), 409, "QA_CONFLICT");
        HttpClient author = client(); JsonNode authorCsrf = csrf(author);
        assertCode(request(author, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "New blocked question", true), authorCsrf, guestToken), 409, "QA_CONFLICT");
        assertCode(request(author, "PUT", qa("/questions/" + question.getId() + "/upvote"), "{\"upvoted\":true}", authorCsrf, guestToken), 409, "QA_CONFLICT");
        assertEquals(0, nativeCount("SELECT version FROM meeting_qa_questions WHERE id=" + question.getId()));
        assertEquals(0, nativeCount("SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=" + meeting.getId()));
        assertEquals(1, body(request(moderator, "GET", qa("/questions"), null, null, null), 200).path("totalElements").asInt());
    }

    @Test void twoModeratorsChangingSettingsWithSameVersionCommitOneChoiceAndConflictTheOther() throws Exception {
        HttpClient first = client(); HttpClient second = client(); JsonNode firstCsrf = signIn(first, owner); JsonNode secondCsrf = signIn(second, secretary.getUserAccount());
        var responses = race(() -> request(first, "PUT", qa("/settings"), settingsJson(true, false, false, 0), firstCsrf, null),
            () -> request(second, "PUT", qa("/settings"), settingsJson(true, true, true, 0), secondCsrf, null));
        assertEquals(1, responses.stream().filter(response -> response.statusCode() == 200).count());
        assertEquals(1, responses.stream().filter(response -> response.statusCode() == 409).count());
        JsonNode winner = body(responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow(), 200);
        JsonNode persisted = body(request(first, "GET", qa("/context"), null, null, null), 200);
        assertEquals(winner, persisted); assertEquals(1, persisted.path("settingsVersion").asInt());
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=" + meeting.getId()));
    }

    @Test void twoModeratorsActingOnSameQuestionVersionCommitOneTransitionAndConflictTheOther() throws Exception {
        var question = seedQuestion(member, "Anonymous race question", MeetingQaStatus.OPEN, true, 0);
        HttpClient first = client(); HttpClient second = client(); JsonNode firstCsrf = signIn(first, owner); JsonNode secondCsrf = signIn(second, secretary.getUserAccount());
        var responses = race(() -> request(first, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("ANSWER", 0), firstCsrf, null),
            () -> request(second, "POST", qa("/questions/" + question.getId() + "/actions"), actionJson("HIDE", 0), secondCsrf, null));
        assertEquals(1, responses.stream().filter(response -> response.statusCode() == 200).count());
        assertEquals(1, responses.stream().filter(response -> response.statusCode() == 409).count());
        JsonNode winner = body(responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow(), 200); assertAnonymous(winner);
        assertEquals(1, winner.path("version").asInt());
        JsonNode persisted = body(request(first, "GET", qa("/questions"), null, null, null), 200).path("content").get(0);
        assertEquals(winner, persisted);
    }

    @Test void askingAgainstDisableCommitsOnlyAcceptedQuestionAndLeavesDisabledContext() throws Exception {
        HttpClient author = client(); JsonNode authorCsrf = csrf(author);
        HttpClient moderator = client(); JsonNode moderatorCsrf = signIn(moderator, owner);
        var outcomes = race(() -> request(author, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Question racing disable", true), authorCsrf, guestToken),
            () -> request(moderator, "PUT", qa("/settings"), settingsJson(false, true, false, 0), moderatorCsrf, null));
        assertEquals(200, outcomes.get(1).statusCode(), outcomes.get(1).body());
        assertTrue(List.of(201, 409).contains(outcomes.get(0).statusCode()), outcomes.get(0).body());
        assertEquals(outcomes.get(0).statusCode() == 201 ? 1 : 0, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        JsonNode context = body(request(author, "GET", qa("/context"), null, null, guestToken), 200);
        assertFalse(context.path("enabled").asBoolean()); assertFalse(context.path("canAsk").asBoolean()); assertFalse(context.path("canUpvote").asBoolean());
    }

    @Test void upvotingAgainstDisableKeepsOnlyCommittedAcceptedVoteAndConsistentCount() throws Exception {
        var question = seedQuestion(member, "Question racing disable", MeetingQaStatus.OPEN, true, 0);
        HttpClient voter = client(); JsonNode voterCsrf = csrf(voter);
        HttpClient moderator = client(); JsonNode moderatorCsrf = signIn(moderator, owner);
        var outcomes = race(() -> request(voter, "PUT", qa("/questions/" + question.getId() + "/upvote"), "{\"upvoted\":true}", voterCsrf, guestToken),
            () -> request(moderator, "PUT", qa("/settings"), settingsJson(false, true, false, 0), moderatorCsrf, null));
        assertEquals(200, outcomes.get(1).statusCode(), outcomes.get(1).body());
        assertTrue(List.of(200, 409).contains(outcomes.get(0).statusCode()), outcomes.get(0).body());
        long accepted = outcomes.get(0).statusCode() == 200 ? 1 : 0;
        assertEquals(accepted, nativeCount("SELECT count(*) FROM meeting_qa_upvotes WHERE question_id=" + question.getId()));
        assertEquals(accepted, nativeCount("SELECT upvote_count FROM meeting_qa_questions WHERE id=" + question.getId()));
    }

    @Test void askingAgainstCanonicalMeetingEndCommitsOnlyQuestionAcceptedBeforeEnd() throws Exception {
        HttpClient author = client(); JsonNode authorCsrf = csrf(author);
        HttpClient moderator = client(); JsonNode moderatorCsrf = signIn(moderator, owner);
        var outcomes = race(() -> request(author, "POST", qa("/questions"), questionJson(UUID.randomUUID().toString(), "Question racing end", true), authorCsrf, guestToken),
            () -> request(moderator, "POST", "/api/meetings/" + meeting.getId() + "/end", null, moderatorCsrf, null));
        assertEquals(204, outcomes.get(1).statusCode(), outcomes.get(1).body());
        assertTrue(List.of(201, 409).contains(outcomes.get(0).statusCode()), outcomes.get(0).body());
        assertEquals(outcomes.get(0).statusCode() == 201 ? 1 : 0, nativeCount("SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=" + meeting.getId()));
        JsonNode context = body(request(moderator, "GET", qa("/context"), null, null, null), 200);
        assertEquals("COMPLETED", context.path("meetingStatus").asText()); assertFalse(context.path("canAsk").asBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"MEMBER", "OWNER", "CHAIRMAN", "SECRETARY"})
    void reportIsAvailableToSessionModeratorsWithoutRoomTokenOrCsrf(String role) throws Exception {
        seedQuestion(guest, "Anonymous history", MeetingQaStatus.PENDING, true, 0);
        HttpClient caller = client(); signIn(caller, moderator(role));
        List<List<String>> rows = csvRows(report(caller, null));
        assertEquals(REPORT_HEADERS, rows.get(0)); assertEquals(2, rows.size());
        assertEquals("Ẩn danh", rows.get(1).get(1)); assertEquals("Chờ duyệt", rows.get(1).get(2));
        verifyNoInteractions(liveKit, capture, stt);
    }

    @ParameterizedTest @ValueSource(strings = {"IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void disabledQaReportRemainsReadOnlyForLiveEndedAndCancelledMeetings(String state) throws Exception {
        var question = seedQuestion(guest, "Preserved anonymous history", MeetingQaStatus.OPEN, true, 0);
        HttpClient moderator = client(); JsonNode csrf = signIn(moderator, owner);
        body(request(moderator, "PUT", qa("/settings"), settingsJson(false, false, true, 0), csrf, null), 200);
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meeting.getId()).setStatus(MeetingStatus.valueOf(state)));
        connectionState(guestToken, "LEFT");
        Map<String, String> before = databaseSnapshot();
        clearInvocations(liveKit, capture, stt);
        List<List<String>> rows = csvRows(report(moderator, null));
        assertEquals("Preserved anonymous history", rows.get(1).get(0)); assertEquals("Ẩn danh", rows.get(1).get(1));
        assertEquals(before, databaseSnapshot()); verifyNoInteractions(liveKit, capture, stt);
        HttpClient memberClient = client(); signIn(memberClient, member.getUserAccount());
        JsonNode history = body(request(memberClient, "GET", qa("/questions"), null, null, null), 200).path("content").get(0);
        assertEquals(question.getId().longValue(), history.path("id").asLong()); assertAnonymous(history);
        assertEquals(REPORT_HEADERS, csvRows(report(memberClient, null)).get(0));
    }

    @Test void reportAllowsParticipantsAndGuestsButRejectsUnrelatedAndRemovedSessions() throws Exception {
        assertReportCode(report(client(), null), "QA_ACCESS_FORBIDDEN");
        HttpClient unrelated = client(); signIn(unrelated, outsider);
        assertReportCode(report(unrelated, null), "QA_MODERATION_FORBIDDEN");
        HttpClient memberClient = client(); signIn(memberClient, member.getUserAccount());
        assertEquals(REPORT_HEADERS, csvRows(report(memberClient, null)).get(0));
        assertEquals(REPORT_HEADERS, csvRows(report(memberClient, memberToken)).get(0));
        assertEquals(REPORT_HEADERS, csvRows(report(client(), memberToken)).get(0));
        assertEquals(REPORT_HEADERS, csvRows(report(client(), guestToken)).get(0));
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, guest.getId()).setRole(MeetingRole.CHAIRMAN));
        assertEquals(REPORT_HEADERS, csvRows(report(client(), guestToken)).get(0));
        tx.executeWithoutResult(status -> em.find(MeetingParticipantEntity.class, secretary.getId()).setStatus(ParticipantStatus.REMOVED));
        HttpClient removedSecretary = client(); signIn(removedSecretary, secretary.getUserAccount());
        assertReportCode(report(removedSecretary, null), "QA_MODERATION_FORBIDDEN");
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void reportValidatesSuppliedTokenWithoutFallbackToModeratorSession() throws Exception {
        HttpClient ownerClient = client(); signIn(ownerClient, owner);
        for (String badToken : List.of("invalid", "", "a".repeat(256), crossMeetingToken)) {
            assertReportCode(report(ownerClient, badToken), "QA_INVALID_PARTICIPANT_TOKEN");
        }
        assertReportCode(report(ownerClient, memberToken), "QA_PARTICIPANT_IDENTITY_MISMATCH");
        assertReportCode(report(ownerClient, guestToken), "QA_PARTICIPANT_IDENTITY_MISMATCH");
        String secretaryToken = tx.execute(status -> connection(em.find(MeetingParticipantEntity.class, secretary.getId())));
        HttpClient secretaryClient = client(); signIn(secretaryClient, secretary.getUserAccount());
        assertEquals(REPORT_HEADERS, csvRows(report(secretaryClient, secretaryToken)).get(0));
        assertEquals(REPORT_HEADERS, csvRows(report(ownerClient, null)).get(0));
    }

    @ParameterizedTest @ValueSource(strings = {"LEFT", "REMOVED"})
    void reportRejectsDisconnectedSuppliedModeratorTokenButAcceptsSessionHistory(String state) throws Exception {
        String token = tx.execute(status -> connection(em.find(MeetingParticipantEntity.class, secretary.getId())));
        HttpClient caller = client(); signIn(caller, secretary.getUserAccount());
        connectionState(token, state);
        assertReportCode(report(caller, token), "QA_CONNECTION_NOT_CONNECTED");
        assertEquals(REPORT_HEADERS, csvRows(report(caller, null)).get(0));
    }

    @Test void pendingModeratorTokenCanReadReportWithoutConfirmingTheConnection() throws Exception {
        String token = tx.execute(status -> connection(em.find(MeetingParticipantEntity.class, secretary.getId())));
        connectionState(token, "PENDING");
        HttpClient caller = client(); signIn(caller, secretary.getUserAccount());
        assertEquals(REPORT_HEADERS, csvRows(report(caller, token)).get(0));
        assertEquals(1, nativeCount("SELECT count(*) FROM meeting_participant_connections WHERE meeting_id=" + meeting.getId()
            + " AND chat_token_hash='" + MeetingChatTokenUtils.sha256(token) + "' AND status='PENDING'"));
    }

    @Test void reportRejectsDisabledCachedModeratorAndDisabledBearerAccounts() throws Exception {
        HttpClient ownerClient = client(); signIn(ownerClient, owner);
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setAccountStatus(AccountStatus.DISABLED));
        assertReportCode(report(ownerClient, null), 401, "ACCOUNT_SESSION_REVOKED");
        HttpClient secretaryClient = client(); signIn(secretaryClient, secretary.getUserAccount());
        String token = tx.execute(status -> connection(em.find(MeetingParticipantEntity.class, secretary.getId())));
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, secretary.getUserAccount().getId()).setAccountStatus(AccountStatus.DISABLED));
        assertReportCode(report(secretaryClient, token), 401, "ACCOUNT_SESSION_REVOKED");
        assertReportCode(report(client(), token), "QA_ACCOUNT_DISABLED");
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void reportWhitelistsColumnsAndAllStatusesWithoutLeakingAnonymousOrOtherMeetingIdentity() throws Exception {
        Map<MeetingQaStatus, String> labels = Map.of(MeetingQaStatus.PENDING, "Chờ duyệt", MeetingQaStatus.OPEN, "Chưa trả lời",
            MeetingQaStatus.ANSWERED, "Đã trả lời", MeetingQaStatus.HIDDEN, "Đã ẩn", MeetingQaStatus.DELETED, "Đã xóa");
        List<MeetingQaQuestionEntity> questions = new ArrayList<>();
        for (MeetingQaStatus state : MeetingQaStatus.values()) questions.add(seedQuestion(guest, "Anonymous " + state, state, true, state.ordinal()));
        var named = seedQuestion(member, "Named question", MeetingQaStatus.OPEN, false, 0); questions.add(named);
        seedQuestion(participantInOtherMeeting(), "Other meeting secret", MeetingQaStatus.OPEN, true, 0);
        LocalDateTime same = LocalDateTime.of(2026, 10, 6, 8, 9, 10);
        tx.executeWithoutResult(status -> {
            for (var question : questions) {
                em.createNativeQuery("UPDATE meeting_qa_questions SET created_at=:time, updated_at=:time, answered_at=:answer WHERE id=:id")
                    .setParameter("time", same).setParameter("answer", question.getStatus() == MeetingQaStatus.ANSWERED ? same.plusMinutes(5) : null)
                    .setParameter("id", question.getId()).executeUpdate();
            }
            em.find(MeetingParticipantEntity.class, member.getId()).setFullName("Renamed member after submission");
        });
        HttpClient caller = client(); signIn(caller, owner);
        var response = report(caller, null); List<List<String>> rows = csvRows(response);
        assertEquals(REPORT_HEADERS, rows.get(0)); assertEquals(questions.size() + 1, rows.size());
        for (int index = 0; index < questions.size(); index++) {
            var question = questions.get(index); var row = rows.get(index + 1);
            assertEquals(8, row.size()); assertEquals(question.getContent(), row.get(0));
            assertEquals(question.isAnonymous() ? "Ẩn danh" : member.getFullName(), row.get(1));
            assertEquals(labels.get(question.getStatus()), row.get(2)); assertEquals(Long.toString(question.getUpvoteCount()), row.get(3));
            assertEquals("06/10/2026 08:09:10", row.get(4));
            assertEquals(question.getStatus() == MeetingQaStatus.ANSWERED ? "06/10/2026 08:14:10" : "", row.get(5));
        }
        String csv = new String(response.body(), StandardCharsets.UTF_8);
        for (String secret : List.of(guest.getFullName(), guest.getEmail(), guest.getParticipantKey(), guest.getUserId(), guestToken,
            member.getUserAccount().getPublicId(), "Other meeting secret", "Renamed member after submission")) assertFalse(csv.contains(secret), secret);
        for (var question : questions) assertFalse(csv.contains(question.getClientQuestionId()));
        assertEquals("attachment; filename=\"hoi-dap-cuoc-hop-" + meeting.getId() + ".csv\"", response.headers().firstValue("Content-Disposition").orElseThrow());
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void reportPreservesUtf8QuotesCommasNewlinesAndNeutralizesWhitespacePrefixedSpreadsheetFormulas() throws Exception {
        List<String> literal = List.of("Câu hỏi tiếng Việt: có đồng ý không? 😀", "Comma, quote \" and CRLF\r\nsecond line", "Line feed\nnext", "Carriage\rnext", "plain - middle", "'Already literal");
        List<String> formulas = List.of("=1+1", "+SUM(1,2)", "-2+3", "@SUM(1,2)", "  =SUM(1,2)", "\t+2", "\r\n-3", "\u00A0@SUM(1,2)",
            "\u2003=4", "\uFEFF+5", "\u000B=6", "\u000C@7", "\u200B=8", "\u2060\t=9");
        for (String content : literal) seedQuestion(member, content, MeetingQaStatus.OPEN, false, 0);
        for (String content : formulas) {
            var question = seedQuestion(member, content, MeetingQaStatus.OPEN, false, 0);
            tx.executeWithoutResult(status -> em.find(MeetingQaQuestionEntity.class, question.getId()).setAuthorDisplayName(content));
        }
        HttpClient caller = client(); signIn(caller, owner);
        var response = report(caller, null); List<List<String>> rows = csvRows(response);
        assertEquals(literal.size() + formulas.size() + 1, rows.size());
        for (int index = 0; index < literal.size(); index++) assertEquals(literal.get(index), rows.get(index + 1).get(0));
        for (int index = 0; index < formulas.size(); index++) {
            var row = rows.get(literal.size() + index + 1);
            assertEquals("'" + formulas.get(index), row.get(0)); assertEquals("'" + formulas.get(index), row.get(1));
        }
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("\"Comma, quote \"\" and CRLF\r\nsecond line\""));
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void reportCrossesDatabaseBatchBoundaryWithoutDroppingDuplicatesOrLoadingOtherMeetingQuestions() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 10, 6, 8, 0);
        tx.executeWithoutResult(status -> {
            for (int index = 0; index < 205; index++) {
                var question = seedQuestion(guest, "Batch question " + index, MeetingQaStatus.OPEN, true, 0);
                em.createNativeQuery("UPDATE meeting_qa_questions SET created_at=:time WHERE id=:id").setParameter("time", same).setParameter("id", question.getId()).executeUpdate();
            }
        });
        seedQuestion(participantInOtherMeeting(), "Another meeting batch secret", MeetingQaStatus.DELETED, true, 0);
        HttpClient caller = client(); signIn(caller, owner);
        List<List<String>> rows = csvRows(report(caller, null)); assertEquals(206, rows.size());
        for (int index = 0; index < 205; index++) { assertEquals("Batch question " + index, rows.get(index + 1).get(0)); assertEquals("Ẩn danh", rows.get(index + 1).get(1)); }
        verifyNoInteractions(liveKit, capture, stt);
    }

    @Test void emptyAndPopulatedReportsNeverCreateSettingsOrAlterAnySourceTableOrCallExternalServices() throws Exception {
        HttpClient caller = client(); signIn(caller, owner);
        Map<String, String> emptyBefore = databaseSnapshot();
        assertEquals(List.of(REPORT_HEADERS), csvRows(report(caller, null)));
        assertEquals(emptyBefore, databaseSnapshot());
        var question = seedQuestion(member, "Existing record", MeetingQaStatus.OPEN, false, 0);
        HttpClient voter = client(); JsonNode voteCsrf = csrf(voter);
        body(request(voter, "PUT", qa("/questions/" + question.getId() + "/upvote"), "{\"upvoted\":true}", voteCsrf, guestToken), 200);
        seedLegacyRecords();
        Map<String, String> populatedBefore = databaseSnapshot(); clearInvocations(liveKit, capture, stt);
        byte[] first = report(caller, null).body(); assertArrayEquals(first, report(caller, null).body());
        assertEquals(populatedBefore, databaseSnapshot()); assertEquals(0, nativeCount("SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=" + meeting.getId()));
        verifyNoInteractions(liveKit, capture, stt);
    }

    private static final List<String> REPORT_HEADERS = List.of("Nội dung câu hỏi", "Người hỏi", "Trạng thái", "Lượt ủng hộ", "Thời gian gửi", "Thời gian trả lời", "Nội dung trả lời", "Người trả lời");
    private HttpResponse<byte[]> report(HttpClient caller, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + qa("/report"))).timeout(Duration.ofSeconds(15))
            .header("Accept", "text/csv");
        if (token != null) builder.header("X-Meeting-Participant-Token", token);
        return caller.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private void assertReportCode(HttpResponse<byte[]> response, String code) throws Exception {
        assertReportCode(response, 403, code);
    }
    private void assertReportCode(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(code, json.readTree(response.body()).path("code").asText());
    }
    /** Independent strict reader: quoted cells, doubled quotes and CRLF record boundaries. */
    private List<List<String>> csvRows(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("text/csv;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        byte[] bytes = response.body(); assertTrue(bytes.length > 3);
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(bytes, 3));
        String csv = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 3, bytes.length - 3)).toString();
        assertTrue(csv.startsWith("\"Nội dung câu hỏi\""));
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); int index = 0;
        while (index < csv.length()) {
            assertEquals('"', csv.charAt(index++), "Each CSV cell must start with a quote");
            StringBuilder value = new StringBuilder(); boolean closed = false;
            while (index < csv.length()) {
                char character = csv.charAt(index++);
                if (character != '"') { value.append(character); continue; }
                if (index < csv.length() && csv.charAt(index) == '"') { value.append('"'); index++; }
                else { closed = true; break; }
            }
            assertTrue(closed, "Unterminated CSV cell"); row.add(value.toString()); assertTrue(index < csv.length(), "Records must end in CRLF");
            if (csv.charAt(index) == ',') { index++; continue; }
            assertEquals('\r', csv.charAt(index++)); assertTrue(index < csv.length()); assertEquals('\n', csv.charAt(index++));
            rows.add(List.copyOf(row)); row.clear();
        }
        assertTrue(row.isEmpty()); return rows;
    }
    private Map<String, String> databaseSnapshot() {
        return tx.execute(status -> {
            // Normal authenticated HTTP access refreshes Spring Session timestamps.
            // Compare every application/domain table, excluding session bookkeeping.
            List<?> tables = em.createNativeQuery("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename NOT IN ('flyway_schema_history','spring_session','spring_session_attributes') ORDER BY tablename").getResultList();
            Map<String, String> snapshot = new LinkedHashMap<>();
            for (Object tableValue : tables) {
                String table = tableValue.toString(); assertTrue(table.matches("[a-z_]+"));
                snapshot.put(table, em.createNativeQuery("SELECT COALESCE(json_agg(t ORDER BY t::text)::text,'[]') FROM " + table + " t").getSingleResult().toString());
            }
            return snapshot;
        });
    }
    private void seedLegacyRecords() {
        tx.executeWithoutResult(status -> {
            var chat = new MeetingChatMessageEntity(); chat.setMeeting(em.find(MeetingEntity.class, meeting.getId()));
            chat.setParticipant(em.find(MeetingParticipantEntity.class, member.getId())); chat.setClientMessageId(UUID.randomUUID().toString());
            chat.setMessageType(MeetingChatMessageType.TEXT); chat.setContent("Existing chat must stay unchanged"); chat.setSenderDisplayName(member.getFullName()); chat.setSentAt(LocalDateTime.now()); em.persist(chat);
            em.createNativeQuery("INSERT INTO meeting_minutes(meeting_id,version,status,source,minutes_json,generated_at,created_at,updated_at) VALUES (:meeting,1,'DRAFT','AI_GENERATED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .setParameter("meeting", meeting.getId()).executeUpdate();
            em.createNativeQuery("INSERT INTO meeting_polls(meeting_id,question,type,status,created_by_account_id,created_at,updated_at) VALUES (:meeting,'Existing poll must stay unchanged','YES_NO_ABSTAIN','DRAFT',:owner,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .setParameter("meeting", meeting.getId()).setParameter("owner", owner.getId()).executeUpdate();
        });
    }

    private void makeConclusionDue() {
        // The HTTP worker is asynchronous in production; make the test job eligible
        // explicitly instead of depending on wall-clock precision at commit time.
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE meeting_conclusions SET available_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE meeting_id=:meeting")
            .setParameter("meeting", meeting.getId()).executeUpdate());
    }

    private String qa(String path) { return "/api/meetings/" + meeting.getId() + "/qa" + path; }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build(); }
    private JsonNode csrf(HttpClient client) throws Exception { return body(request(client, "GET", "/api/auth/csrf", null, null, null), 200); }
    private JsonNode signIn(HttpClient client, UserAccountEntity account) throws Exception {
        var response = request(client, "POST", "/api/auth/login", json.writeValueAsString(Map.of("usernameOrEmail", account.getUsername(), "password", PASSWORD)), csrf(client), null);
        assertEquals(200, response.statusCode(), response.body()); return csrf(client);
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String payload, JsonNode csrf, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").header("Content-Type", "application/json");
        if (csrf != null) builder.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        if (token != null) builder.header("X-Meeting-Participant-Token", token);
        return client.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response, int status) throws Exception { assertEquals(status, response.statusCode(), response.body()); return json.readTree(response.body()); }
    private void assertCode(HttpResponse<String> response, int status, String code) throws Exception { assertEquals(code, body(response, status).path("code").asText()); }
    private String questionJson(String retry, String content, boolean anonymous) throws Exception { return json.writeValueAsString(Map.of("clientQuestionId", retry, "content", content, "anonymous", anonymous)); }
    private String settingsJson(boolean enabled, boolean anonymous, boolean moderation, long version) throws Exception { return json.writeValueAsString(Map.of("enabled", enabled, "anonymousAllowed", anonymous, "moderationEnabled", moderation, "expectedVersion", version)); }
    private String actionJson(String action, long version) throws Exception { return json.writeValueAsString(Map.of("action", action, "expectedVersion", version)); }
    private UserAccountEntity moderator(String role) {
        return switch (role) {
            case "OWNER" -> owner;
            case "SECRETARY" -> secretary.getUserAccount();
            case "MEMBER" -> member.getUserAccount();
            case "CHAIRMAN" -> tx.execute(status -> participant(em.find(MeetingEntity.class, meeting.getId()), account(SystemRole.USER, "Chairman"), MeetingRole.CHAIRMAN).getUserAccount());
            default -> throw new IllegalArgumentException(role);
        };
    }
    private void assertAnonymous(JsonNode question) {
        assertTrue(question.path("anonymous").asBoolean()); assertEquals("Ẩn danh", question.path("authorDisplayName").asText());
        for (String field : List.of("participantId", "participant", "participantConnectionId", "connectionId", "attendeeId", "userAccountId", "accountId", "moderatedBy", "chatWriteToken", "chatTokenHash")) assertFalse(question.has(field), field);
        assertFalse(question.toString().contains(guest.getFullName())); assertFalse(question.toString().contains(guestToken));
    }
    private List<Long> ids(JsonNode page) { List<Long> result = new ArrayList<>(); page.path("content").forEach(q -> result.add(q.path("id").asLong())); return result; }
    private UserAccountEntity account(SystemRole role, String name) {
        var account = new UserAccountEntity(); String key = UUID.randomUUID().toString();
        account.setPublicId(key); account.setNormalizedEmail(key + "@example.test"); account.setUsername(key);
        account.setFullName(name); account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(role);
        account.setPasswordHash(encoder.encode(PASSWORD)); account.setEmailVerified(true); em.persist(account); return account;
    }
    private MeetingEntity meeting(UserAccountEntity owner) {
        var meeting = new MeetingEntity(); String key = UUID.randomUUID().toString().replace("-", "");
        meeting.setMeetingCode(key); meeting.setRoomName(key); meeting.setTitle("Isolated Q&A runtime");
        meeting.setStartTime(LocalDateTime.now().minusHours(1)); meeting.setEndTime(LocalDateTime.now().plusHours(1));
        meeting.setStatus(MeetingStatus.IN_PROGRESS); meeting.setAccessType(MeetingAccessType.PUBLIC); meeting.setCreatedByAccount(owner); em.persist(meeting); return meeting;
    }
    private MeetingParticipantEntity participant(MeetingEntity meeting, UserAccountEntity account, MeetingRole role) {
        var participant = new MeetingParticipantEntity(); String key = UUID.randomUUID().toString();
        participant.setMeeting(meeting); participant.setUserAccount(account); participant.setParticipantKey(key);
        participant.setUserId(account == null ? "guest-" + key : "account-" + account.getPublicId());
        participant.setFullName(account == null ? "Private guest identity" : account.getFullName());
        participant.setEmail(account == null ? key + "@guest.local" : account.getNormalizedEmail());
        participant.setRole(role); participant.setStatus(ParticipantStatus.JOINED);
        participant.setJoinType(account == null ? ParticipantJoinType.GUEST : ParticipantJoinType.AUTHENTICATED); em.persist(participant); return participant;
    }
    private String connection(MeetingParticipantEntity participant) {
        String token = UUID.randomUUID().toString(); var connection = new MeetingParticipantConnectionEntity();
        connection.setMeeting(participant.getMeeting()); connection.setParticipant(participant);
        connection.setAttendeeId(participant.getUserAccount() == null ? "guest:" + participant.getParticipantKey() : "account:" + participant.getUserAccount().getPublicId());
        connection.setConnectionId(UUID.randomUUID().toString()); connection.setLiveKitIdentity(connection.getAttendeeId() + ":" + connection.getConnectionId());
        connection.setJoinedAt(LocalDateTime.now()); connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        connection.setChatTokenHash(MeetingChatTokenUtils.sha256(token)); em.persist(connection); return token;
    }
    private void connectionState(String token, String status) { tx.executeWithoutResult(state -> em.createNativeQuery("UPDATE meeting_participant_connections SET status=:status WHERE chat_token_hash=:hash").setParameter("status", status).setParameter("hash", MeetingChatTokenUtils.sha256(token)).executeUpdate()); }
    private MeetingParticipantEntity participantInOtherMeeting() { return tx.execute(status -> participant(em.find(MeetingEntity.class, otherMeeting.getId()), null, MeetingRole.GUEST)); }
    private MeetingQaQuestionEntity seedQuestion(MeetingParticipantEntity author, String content, MeetingQaStatus status, boolean anonymous, long votes) {
        return tx.execute(transaction -> {
            var question = new MeetingQaQuestionEntity(); question.setMeeting(em.find(MeetingEntity.class, author.getMeeting().getId())); question.setParticipant(em.find(MeetingParticipantEntity.class, author.getId()));
            question.setClientQuestionId(UUID.randomUUID().toString()); question.setContent(content); question.setAnonymous(anonymous);
            question.setAuthorDisplayName(anonymous ? null : author.getFullName()); question.setStatus(status); question.setUpvoteCount(votes);
            if (status == MeetingQaStatus.ANSWERED) question.setAnsweredAt(LocalDateTime.now()); em.persist(question); return question;
        });
    }
    private long nativeCount(String query) { return tx.execute(status -> ((Number) em.createNativeQuery(query).getSingleResult()).longValue()); }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown(); return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    private static String url() { return System.getProperty("qna.postgres.url"); }
    private static String username() { return System.getProperty("qna.postgres.username", "qna_test"); }
    private static String password() { return Objects.requireNonNull(System.getenv("QNA_TEST_DB_PASSWORD")); }
}
