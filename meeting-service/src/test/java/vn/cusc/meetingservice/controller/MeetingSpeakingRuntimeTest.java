package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
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
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.client.conclusion.ConclusionClient;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.MeetingCaptureService;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

/** Real HTTP, session, CSRF, service transactions and PostgreSQL in an owned schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingSpeakingRuntimeTest {
    private static final String SCHEMA = "qna_p2_point_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "Isolated-Point-Test-2026!";
    private static boolean schemaCreated;
    private static String passwordHash;
    @LocalServerPort private int port;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockBean private LiveKitService liveKit;
    @MockBean private MeetingCaptureService capture;
    @MockBean private SttClient stt;
    @MockBean private ConclusionClient conclusion;
    private TransactionTemplate tx;
    private UserAccountEntity owner, chairman, secretary, member, outsider;
    private Browser ownerBrowser, chairmanBrowser, secretaryBrowser, memberBrowser, guestBrowser;
    private long meetingId, otherMeetingId, pointA, pointB, otherPoint;
    private String memberToken, guestToken, guestId;

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA); schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", MeetingSpeakingRuntimeTest::username);
        properties.add("spring.datasource.password", MeetingSpeakingRuntimeTest::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @BeforeEach void fixture() throws Exception {
        tx = new TransactionTemplate(transactions);
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        tx.executeWithoutResult(status -> {
            owner = account("Meeting owner"); chairman = account("Chairman"); secretary = account("Secretary");
            member = account("Individual attendee"); outsider = account("Other signed-in attendee");
        });
        when(liveKit.createRoom(anyString())).thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_point", 0, 0));
        when(liveKit.ensureRoomExists(anyString())).thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_point", 0, 0));
        when(liveKit.generateJoinToken(anyString(), anyString(), anyString(), anyMap(), eq(true), eq(true))).thenReturn("isolated-jwt");
        ownerBrowser = signedIn(owner); chairmanBrowser = signedIn(chairman); secretaryBrowser = signedIn(secretary);
        memberBrowser = signedIn(member); guestBrowser = browser();
        meetingId = createPoint(List.of("School A", "School B"), true);
        otherMeetingId = createPoint(List.of("Other school"), false);
        var info = body(send(guestBrowser, "GET", base(meetingId) + "/join-info", null, null, false), 200);
        pointA = info.path("points").get(0).path("id").asLong(); pointB = info.path("points").get(1).path("id").asLong();
        otherPoint = body(send(guestBrowser, "GET", base(otherMeetingId) + "/join-info", null, null, false), 200)
            .path("points").get(0).path("id").asLong();
        memberToken = join(memberBrowser, pointA, "AUTHENTICATED", "Ignored account claim").path("participant").path("chatWriteToken").asText();
        var guestJoin = join(guestBrowser, pointB, "GUEST", "Shared room representative");
        guestToken = guestJoin.path("participant").path("chatWriteToken").asText();
        guestId = guestJoin.path("participant").path("attendeeId").asText().replace("guest:", "guest-");
        clearInvocations(liveKit, capture, stt, conclusion);
    }

    @AfterEach void cleanupFixture() {
        verifyNoInteractions(stt, conclusion);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM meetings").executeUpdate());
    }

    @AfterAll static void dropOnlyOwnedSchema() throws Exception {
        if (!schemaCreated) return;
        assertTrue(SCHEMA.matches("qna_p2_point_http_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void pointCreationIsPublicAndSafeJoinMetadataIncludesOrderedPoints() throws Exception {
        var info = body(send(guestBrowser, "GET", base(meetingId) + "/join-info", null, null, false), 200);
        assertEquals("POINT", info.path("creationMode").asText()); assertEquals("PUBLIC", info.path("accessType").asText());
        assertEquals(List.of("School A", "School B"), strings(info.path("points"), "name"));
        for (String field : List.of("participants", "documents", "email", "roomName", "roomSid", "createdByAccount")) assertFalse(info.has(field), field);
        var viewer = signedIn(outsider);
        assertEquals("POINT", body(send(viewer, "GET", base(meetingId), null, null, false), 200).path("creationMode").asText());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void concurrentPointCreationWithSameClientKeyCreatesExactlyOneMeetingAndOneExternalRoom() throws Exception {
        String requestId = UUID.randomUUID().toString(), payload = pointCreatePayload(List.of("Retry school"), false, requestId);
        long before = count("meetings");
        var replies = race(() -> send(ownerBrowser, "POST", "/api/meetings", payload, null, true),
            () -> send(ownerBrowser, "POST", "/api/meetings", payload, null, true));
        var first = body(replies.get(0), 201); var second = body(replies.get(1), 201);
        assertEquals(first.path("id"), second.path("id")); assertEquals(first.path("roomName"), second.path("roomName"));
        assertEquals(before + 1, count("meetings")); verify(liveKit, times(1)).createRoom(anyString()); verifyNoInteractions(capture);
    }

    @Test void pointCreationReceiptSurvivesNewLoginAndPostCreationEditsWithoutRecreatingRoom() throws Exception {
        String payload = pointCreatePayload(List.of("Retry school"), false, UUID.randomUUID().toString());
        var first = body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        long id = first.path("id").asLong();
        var saved = body(send(ownerBrowser, "GET", base(id), null, null, false), 200);
        var edit = Map.of("title", "Edited after original receipt", "startTime", saved.path("startTime").asText(), "endTime", saved.path("endTime").asText(), "accessType", "PUBLIC");
        body(send(ownerBrowser, "PUT", base(id), json.writeValueAsString(edit), null, true), 200);
        var newSession = signedIn(owner);
        var retried = body(send(newSession, "POST", "/api/meetings", payload, null, true), 201);
        assertEquals(first.path("id"), retried.path("id")); assertEquals(first.path("roomName"), retried.path("roomName"));
        assertEquals("Edited after original receipt", body(send(newSession, "GET", base(id), null, null, false), 200).path("title").asText());
        verify(liveKit, times(1)).createRoom(anyString()); verifyNoInteractions(capture);
    }

    @Test void uncertainExternalRoomCreationRetryReusesSameRoomNameAndCommitsOneMeeting() throws Exception {
        var externalRooms = ConcurrentHashMap.<String>newKeySet(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(liveKit.createRoom(anyString())).thenAnswer(invocation -> {
            String roomName = invocation.getArgument(0); externalRooms.add(roomName);
            if (calls.getAndIncrement() == 0) throw new LiveKitOperationException("Simulated response timeout after external room creation");
            return new LiveKitRoomResponse(roomName, "RM_retry", 0, 0);
        });
        String payload = pointCreatePayload(List.of("Uncertain room school"), false, UUID.randomUUID().toString());
        long before = count("meetings");
        body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 502);
        assertEquals(before, count("meetings"));
        var retried = body(send(signedIn(owner), "POST", "/api/meetings", payload, null, true), 201);
        assertEquals(before + 1, count("meetings")); assertEquals(1, externalRooms.size());
        assertTrue(externalRooms.contains(retried.path("roomName").asText())); verify(liveKit, times(2)).createRoom(anyString()); verifyNoInteractions(capture);
    }

    @Test void pointCreationCanonicalBodyAllowsWhitespaceAndRosterOrderingWithoutNewMeeting() throws Exception {
        String payload = pointCreatePayload(List.of("School A", "School B"), true, UUID.randomUUID().toString());
        var original = body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        var normalizedRetry = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(payload);
        normalizedRetry.put("title", "  " + normalizedRetry.path("title").asText() + "  ");
        normalizedRetry.set("pointNames", json.valueToTree(List.of(" School A ", " School B ")));
        var roster = normalizedRetry.path("participants"); normalizedRetry.set("participants", json.valueToTree(List.of(roster.get(1), roster.get(0))));
        var retry = body(send(ownerBrowser, "POST", "/api/meetings", json.writeValueAsString(normalizedRetry), null, true), 201);
        assertEquals(original.path("id"), retry.path("id")); verify(liveKit, times(1)).createRoom(anyString()); verifyNoInteractions(capture);
    }

    @Test void pointCreationKeyCannotBeReusedForDifferentValidTitlePointsScheduleOrRoster() throws Exception {
        String requestId = UUID.randomUUID().toString(), payload = pointCreatePayload(List.of("Original school"), false, requestId);
        var first = body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        long before = count("meetings");
        for (String field : List.of("title", "pointNames", "endTime", "participants")) {
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(payload);
            switch (field) {
                case "title" -> changed.put("title", "Different title");
                case "pointNames" -> changed.set("pointNames", json.valueToTree(List.of("Different school")));
                case "endTime" -> changed.put("endTime", LocalDateTime.parse(changed.path("endTime").asText()).plusHours(1).toString());
                default -> changed.set("participants", json.valueToTree(List.of(Map.of("userPublicId", chairman.getPublicId(), "role", "CHAIRMAN"))));
            }
            body(send(ownerBrowser, "POST", "/api/meetings", json.writeValueAsString(changed), null, true), 409);
        }
        assertEquals(before, count("meetings")); assertTrue(first.path("id").asLong() > 0); verify(liveKit, times(1)).createRoom(anyString());
    }

    @Test void samePointCreateKeyIsScopedByActualCreatorRatherThanCallerSuppliedIdentity() throws Exception {
        String payload = pointCreatePayload(List.of("Actor scoped school"), false, UUID.randomUUID().toString());
        var own = body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        var other = body(send(signedIn(outsider), "POST", "/api/meetings", payload, null, true), 201);
        assertNotEquals(own.path("id"), other.path("id")); assertNotEquals(own.path("roomName"), other.path("roomName"));
        tx.executeWithoutResult(status -> {
            assertEquals(owner.getId(), em.find(MeetingEntity.class, own.path("id").asLong()).getCreatedByAccount().getId());
            assertEquals(outsider.getId(), em.find(MeetingEntity.class, other.path("id").asLong()).getCreatedByAccount().getId());
        });
        verify(liveKit, times(2)).createRoom(anyString());
    }

    @Test void invalidPointCreationRetryKeysRejectBeforeExternalRoomOrPersistentMeeting() throws Exception {
        long before = count("meetings");
        for (String invalid : List.of("", "not-a-uuid", "x".repeat(100))) {
            body(send(ownerBrowser, "POST", "/api/meetings", pointCreatePayload(List.of("School"), false, invalid), null, true), 400);
        }
        assertEquals(before, count("meetings")); verifyNoInteractions(liveKit, capture);
    }

    @Test void objectPointCreationKeyReturnsSanitizedClientErrorWithoutCreatingRoomOrMeeting() throws Exception {
        long before = count("meetings");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(pointCreatePayload(List.of("School"), false, UUID.randomUUID().toString()));
        payload.set("clientRequestId", json.valueToTree(Map.of("unexpected", "object")));
        var failure = body(send(ownerBrowser, "POST", "/api/meetings", json.writeValueAsString(payload), null, true), 400);
        assertEquals("MEETING_INVALID_REQUEST", failure.path("code").asText());
        assertFalse(failure.path("message").asText().contains("com.fasterxml")); assertFalse(failure.path("message").asText().contains("vn.cusc"));
        assertEquals(before, count("meetings")); verifyNoInteractions(liveKit, capture);
    }

    @Test void objectJoinPointReturnsSanitizedClientErrorWithoutCreatingConnectionOrRoom() throws Exception {
        long before = count("meeting_participant_connections");
        var payload = Map.of("participantType", "GUEST", "displayName", "Bad point field", "pointId", Map.of("unexpected", "object"));
        var failure = body(send(guestBrowser, "POST", base(meetingId) + "/join", json.writeValueAsString(payload), null, true), 400);
        assertEquals("MEETING_INVALID_REQUEST", failure.path("code").asText());
        assertFalse(failure.path("message").asText().contains("com.fasterxml")); assertFalse(failure.path("message").asText().contains("vn.cusc"));
        assertEquals(before, count("meeting_participant_connections")); verifyNoInteractions(liveKit, capture);
    }

    @Test void deletedPointCreationReceiptCannotRecreateMeetingWhenAnOldClientRetries() throws Exception {
        String payload = pointCreatePayload(List.of("Deleted receipt school"), false, UUID.randomUUID().toString());
        var created = body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        body(send(ownerBrowser, "DELETE", base(created.path("id").asLong()), null, null, true), 204);
        verify(capture).finalizeForTerminalTransition(created.path("id").asLong());
        clearInvocations(capture);
        long before = count("meetings"); clearInvocations(liveKit);
        var retry = body(send(signedIn(owner), "POST", "/api/meetings", payload, null, true), 410);
        assertEquals("MEETING_CREATION_RECEIPT_DELETED", retry.path("code").asText());
        assertEquals(before, count("meetings")); verifyNoInteractions(liveKit, capture);
    }

    @Test void expiredOrDisabledCreatorCannotReplayAnotherAccountsCreationReceipt() throws Exception {
        String payload = pointCreatePayload(List.of("Protected receipt school"), false, UUID.randomUUID().toString());
        body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        clearInvocations(liveKit);
        body(send(browser(), "POST", "/api/meetings", payload, null, true), 401);
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setAccountStatus(AccountStatus.DISABLED));
        assertEquals("ACCOUNT_SESSION_REVOKED", body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 401).path("code").asText());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void joinRejectsMissingInvalidAndOtherMeetingPointsBeforeCreatingConnectionOrExternalRoom() throws Exception {
        long before = count("meeting_participant_connections");
        for (Long selected : Arrays.asList(null, -1L, otherPoint)) {
            var input = new LinkedHashMap<String, Object>(); input.put("participantType", "GUEST"); input.put("displayName", "Bad selection");
            if (selected != null) input.put("pointId", selected);
            body(send(guestBrowser, "POST", base(meetingId) + "/join", json.writeValueAsString(input), null, true), 400);
        }
        assertEquals(before, count("meeting_participant_connections")); verifyNoInteractions(liveKit, capture);
    }

    @Test void authenticatedJoinUsesServerAccountNameAndEachConnectionRetainsItsOwnPoint() throws Exception {
        var second = join(memberBrowser, pointB, "AUTHENTICATED", "Spoofed name");
        assertEquals(member.getFullName(), second.path("participant").path("displayName").asText());
        assertEquals(pointB, second.path("participant").path("pointId").asLong());
        var firstContext = context(memberBrowser, memberToken); var secondContext = context(memberBrowser, second.path("participant").path("chatWriteToken").asText());
        assertEquals(pointA, firstContext.path("pointId").asLong()); assertEquals(pointB, secondContext.path("pointId").asLong());
    }

    @Test void secureGuestResumePreservesLogicalActorAndRetryReceiptAfterRefresh() throws Exception {
        String id = UUID.randomUUID().toString();
        var waiting = ask(guestBrowser, guestToken, id, "Shared speaker");
        setConnectionStatus(guestToken, "LEFT");
        var refreshedBrowser = browser();
        var rejoined = body(send(refreshedBrowser, "POST", base(meetingId) + "/join", guestResumePayload(guestId, guestToken, pointB), null, true), 200);
        assertEquals(guestId, rejoined.path("participant").path("attendeeId").asText().replace("guest:", "guest-"));
        String resumedToken = rejoined.path("participant").path("chatWriteToken").asText();
        assertNotEquals(guestToken, resumedToken);
        var receipt = ask(refreshedBrowser, resumedToken, id, "Shared speaker");
        assertEquals(waiting.path("id"), receipt.path("id")); assertEquals(waiting.path("requestedAt"), receipt.path("requestedAt"));
        assertTrue(receipt.path("isOwn").asBoolean()); assertEquals(1, count("meeting_speaking_requests"));
        assertEquals("CANCELLED", action(refreshedBrowser, resumedToken, receipt, "cancel", 200).path("status").asText());
    }

    @Test void publicGuestIdWithoutSecretCannotTakeOverPreviousActorOrRequest() throws Exception {
        var waiting = ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Original speaker");
        var stranger = browser();
        var rejoined = body(send(stranger, "POST", base(meetingId) + "/join", guestResumePayload(guestId, null, pointB), null, true), 200);
        assertNotEquals(guestId, rejoined.path("participant").path("attendeeId").asText().replace("guest:", "guest-"));
        String strangerToken = rejoined.path("participant").path("chatWriteToken").asText();
        assertFalse(list(stranger, strangerToken).path("content").get(0).path("isOwn").asBoolean());
        action(stranger, strangerToken, waiting, "cancel", 403);
        assertEquals("WAITING", list(guestBrowser, guestToken).path("content").get(0).path("status").asText());
    }

    @Test void wrongGuestResumeProofAndAuthenticatedTokenCannotResumeGuest() throws Exception {
        long before = count("meeting_participant_connections");
        for (String proof : List.of(UUID.randomUUID().toString(), memberToken)) {
            body(send(browser(), "POST", base(meetingId) + "/join", guestResumePayload(guestId, proof, pointB), null, true), 403);
        }
        body(send(browser(), "POST", base(otherMeetingId) + "/join", guestResumePayload(guestId, guestToken, otherPoint), null, true), 403);
        assertEquals(before, count("meeting_participant_connections")); verifyNoInteractions(liveKit, capture);
    }

    @Test void removedGuestCannotResumeWithPreviouslyValidSecret() throws Exception {
        setConnectionStatus(guestToken, "REMOVED");
        body(send(browser(), "POST", base(meetingId) + "/join", guestResumePayload(guestId, guestToken, pointB), null, true), 403);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void blankSpeakerUsesAccountNameAndSharedInputKeepsPointAndRequesterSnapshots() throws Exception {
        var own = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "  ");
        assertEquals("Individual attendee", own.path("speakerName").asText()); assertEquals("School A", own.path("pointName").asText());
        assertEquals("Individual attendee", own.path("requestedByDisplayName").asText()); assertTrue(own.path("isOwn").asBoolean());
        var shared = ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "  Nguyễn Văn Bình  ");
        assertEquals("Nguyễn Văn Bình", shared.path("speakerName").asText()); assertEquals("School B", shared.path("pointName").asText());
        assertEquals("Shared room representative", shared.path("requestedByDisplayName").asText()); assertEquals("WAITING", shared.path("status").asText());
        assertTrue(shared.path("approvedAt").isNull()); assertTrue(shared.path("finishedAt").isNull());
        var history = list(memberBrowser, memberToken);
        assertEquals(2, history.path("totalElements").asInt()); assertEquals(2, history.path("content").size());
        for (var entry : history.path("content")) {
            for (String secret : List.of("chatTokenHash", "chatWriteToken", "connectionId", "participantId", "userAccountId")) assertFalse(entry.has(secret), secret);
        }
        verifyNoInteractions(liveKit, capture);
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "CHAIRMAN", "SECRETARY"})
    void onlyDesignatedModeratorsApproveFinishAndRecordServerTimestamps(String role) throws Exception {
        var waiting = ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaker");
        var moderator = switch (role) { case "OWNER" -> ownerBrowser; case "CHAIRMAN" -> chairmanBrowser; default -> secretaryBrowser; };
        assertTrue(context(moderator, null).path("canModerate").asBoolean());
        var approved = action(moderator, null, waiting, "approve", 200);
        assertEquals("SPEAKING", approved.path("status").asText()); assertEquals(1, approved.path("version").asLong());
        var finished = action(moderator, null, approved, "finish", 200);
        assertEquals("FINISHED", finished.path("status").asText()); assertEquals(2, finished.path("version").asLong());
        assertFalse(finished.path("approvedByDisplayName").asText().isBlank()); assertFalse(finished.path("finishedByDisplayName").asText().isBlank());
        assertTrue(finished.path("requestedAt").asText().endsWith("Z"));
        var requestedAt = Instant.parse(finished.path("requestedAt").asText());
        var approvedAt = Instant.parse(finished.path("approvedAt").asText()); var finishedAt = Instant.parse(finished.path("finishedAt").asText());
        assertFalse(approvedAt.isBefore(requestedAt)); assertFalse(finishedAt.isBefore(approvedAt));
        assertTrue(Duration.between(requestedAt, Instant.now()).abs().compareTo(Duration.ofSeconds(30)) < 0, "Timeline must use the actual UTC instant");
        assertEquals(finished.path("id").asLong(), list(memberBrowser, memberToken).path("content").get(0).path("id").asLong());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void ordinaryMemberGuestAndUnrelatedSignedInUserCannotApproveOrFinishOthersRequests() throws Exception {
        var waiting = ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaker");
        assertFalse(context(memberBrowser, memberToken).path("canModerate").asBoolean());
        action(memberBrowser, memberToken, waiting, "approve", 403); action(guestBrowser, guestToken, waiting, "approve", 403);
        action(signedIn(outsider), null, waiting, "approve", 403);
        var approved = action(ownerBrowser, null, waiting, "approve", 200);
        action(memberBrowser, memberToken, approved, "finish", 403); action(signedIn(outsider), null, approved, "finish", 403);
        assertEquals("SPEAKING", list(memberBrowser, memberToken).path("content").get(0).path("status").asText());
        assertEquals("FINISHED", action(guestBrowser, guestToken, approved, "finish", 200).path("status").asText());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void unrelatedSystemAdminDoesNotGainPointSpeakingModeration() throws Exception {
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, outsider.getId()).setSystemRole(SystemRole.ADMIN));
        var admin = signedIn(outsider);
        assertFalse(context(admin, null).path("canModerate").asBoolean());
        action(admin, null, ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaker"), "approve", 403);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void ownerDesignatedGuestOfficerUsesConnectionProofAndImmediatelyLosesModerationAfterDemotion() throws Exception {
        var roster = body(send(ownerBrowser, "GET", base(meetingId) + "/participants", null, null, false), 200);
        JsonNode guest = null;
        for (var row : roster) if (row.path("userId").asText().equals(guestId)) guest = row;
        assertNotNull(guest); String guestKey = guest.path("participantKey").asText();
        var promoted = updatePayload("Guest designated by creator");
        promoted.put("participants", List.of(Map.of("participantKey", guestKey, "role", "CHAIRMAN")));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(promoted), null, true), 200);
        assertTrue(context(guestBrowser, guestToken).path("canModerate").asBoolean());
        var first = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "First speaker");
        assertEquals("SPEAKING", action(guestBrowser, guestToken, first, "approve", 200).path("status").asText());
        var demoted = updatePayload("Guest demoted by creator");
        demoted.put("participants", List.of(Map.of("participantKey", guestKey, "role", "GUEST")));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(demoted), null, true), 200);
        assertFalse(context(guestBrowser, guestToken).path("canModerate").asBoolean());
        action(guestBrowser, guestToken, ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Second speaker"), "approve", 403);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void creatorConnectionProofKeepsModerationAfterSessionExpiryButFreshDisabledStateStillRevokesIt() throws Exception {
        var creatorJoin = join(ownerBrowser, pointA, "AUTHENTICATED", "Ignored creator name");
        String creatorToken = creatorJoin.path("participant").path("chatWriteToken").asText();
        clearInvocations(liveKit);
        var afterSessionExpiry = browser();
        assertTrue(context(afterSessionExpiry, creatorToken).path("canModerate").asBoolean());
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        var approved = action(afterSessionExpiry, creatorToken, waiting, "approve", 200);
        assertEquals(owner.getFullName(), approved.path("approvedByDisplayName").asText());
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setAccountStatus(AccountStatus.DISABLED));
        body(send(afterSessionExpiry, "GET", speaking("/context"), null, creatorToken, false), 403);
        action(afterSessionExpiry, creatorToken, approved, "finish", 403);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void requestsAndMutationsRequireCsrfEvenWithValidParticipantToken() throws Exception {
        var payload = payload(UUID.randomUUID().toString(), "Speaker");
        body(send(memberBrowser, "POST", speaking("/requests"), payload, memberToken, false), 403);
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        body(send(ownerBrowser, "PATCH", speaking("/requests/" + waiting.path("id").asLong() + "/approve"), version(waiting), null, false), 403);
        assertEquals("WAITING", list(memberBrowser, memberToken).path("content").get(0).path("status").asText());
    }

    @Test void participantTokenCannotBeUsedByAnotherSessionOrForAnotherMeeting() throws Exception {
        var wrongSession = signedIn(outsider);
        body(send(wrongSession, "GET", speaking("/context"), null, memberToken, false), 403);
        body(send(wrongSession, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Spoofed"), memberToken, true), 403);
        body(send(memberBrowser, "GET", base(otherMeetingId) + "/speaking/context", null, memberToken, false), 403);
        assertEquals(0, count("meeting_speaking_requests")); verifyNoInteractions(liveKit, capture);
    }

    @Test void missingTokenCannotCreateRequestEvenForOwnerAndAnonymousCannotReadWithoutConnectionProof() throws Exception {
        body(send(ownerBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Owner"), null, true), 403);
        body(send(browser(), "GET", speaking("/requests"), null, null, false), 403);
        assertFalse(context(ownerBrowser, null).path("canRequest").asBoolean());
        assertEquals(0, count("meeting_speaking_requests"));
    }

    @ParameterizedTest @ValueSource(strings = {"LEFT", "REMOVED"})
    void staleConnectionTokensCannotRequestOrRead(String state) throws Exception {
        setConnectionStatus(memberToken, state);
        body(send(memberBrowser, "GET", speaking("/context"), null, memberToken, false), 403);
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Stale"), memberToken, true), 403);
    }

    @Test void disablingAccountAfterLoginRevokesSpeakingReadsAndWrites() throws Exception {
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, member.getId()).setAccountStatus(AccountStatus.DISABLED));
        assertEquals("ACCOUNT_SESSION_REVOKED", body(send(memberBrowser, "GET", speaking("/context"), null, memberToken, false), 401).path("code").asText());
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Disabled"), memberToken, true), 403);
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setAccountStatus(AccountStatus.DISABLED));
        assertEquals("ACCOUNT_SESSION_REVOKED", body(send(ownerBrowser, "GET", speaking("/context"), null, null, false), 401).path("code").asText());
    }

    @Test void disablingPointCreatorRevokesMetadataRosterAndEndActionsBeforeExternalCalls() throws Exception {
        var metadata = updatePayload("Disabled owner cannot save");
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setAccountStatus(AccountStatus.DISABLED));
        assertEquals("ACCOUNT_SESSION_REVOKED", body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(metadata), null, true), 401).path("code").asText());
        body(send(ownerBrowser, "POST", base(meetingId) + "/end", "{}", null, true), 401);
        body(send(ownerBrowser, "POST", base(meetingId) + "/participants", json.writeValueAsString(Map.of("userPublicId", outsider.getPublicId(), "role", "MEMBER")), null, true), 401);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void removingParticipantOrDemotingSecretaryIsCheckedFromCurrentDatabaseState() throws Exception {
        var waiting = ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaker");
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE meeting_participants SET role='MEMBER' WHERE meeting_id=:meeting AND user_account_id=:account")
            .setParameter("meeting", meetingId).setParameter("account", secretary.getId()).executeUpdate());
        action(secretaryBrowser, null, waiting, "approve", 403);
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE meeting_participants SET status='REMOVED' WHERE meeting_id=:meeting AND user_account_id=:account")
            .setParameter("meeting", meetingId).setParameter("account", member.getId()).executeUpdate());
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Removed"), memberToken, true), 403);
    }

    @Test void duplicateConcurrentRequestHasOneIdAndChangedRetryPayloadConflicts() throws Exception {
        String id = UUID.randomUUID().toString(), input = payload(id, "Shared speaker");
        var results = race(() -> send(guestBrowser, "POST", speaking("/requests"), input, guestToken, true),
            () -> send(guestBrowser, "POST", speaking("/requests"), input, guestToken, true));
        var first = body(results.get(0), 201); var second = body(results.get(1), 201);
        assertEquals(first, second); assertEquals(1, count("meeting_speaking_requests"));
        body(send(guestBrowser, "POST", speaking("/requests"), payload(id, "Changed speaker"), guestToken, true), 409);
        assertEquals(1, count("meeting_speaking_requests"));
    }

    @Test void sameSpeakerCannotAccumulateDuplicateActiveRequestsButSharedRoomCanQueueDifferentPeople() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "First");
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), " First "), memberToken, true), 409);
        var approved = action(ownerBrowser, null, waiting, "approve", 200);
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "First"), memberToken, true), 409);
        assertEquals("WAITING", ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Second").path("status").asText());
        action(ownerBrowser, null, approved, "finish", 200);
        assertEquals("WAITING", ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "First").path("status").asText());
    }

    @Test void transitionRetriesPreserveReceiptAndStaleDifferentActionsConflict() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        var approved = action(ownerBrowser, null, waiting, "approve", 200);
        assertEquals(approved, action(ownerBrowser, null, waiting, "approve", 200));
        action(ownerBrowser, null, waiting, "finish", 409);
        var finished = action(ownerBrowser, null, approved, "finish", 200);
        assertEquals(finished, action(ownerBrowser, null, approved, "finish", 200));
        assertEquals("FINISHED", finished.path("status").asText());
        assertEquals(finished.path("requestedAt"), waiting.path("requestedAt"));
    }

    @Test void cancelRequiresOwnershipOrModeratorAndOnlyPendingRequestCanBeCancelled() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        action(guestBrowser, guestToken, waiting, "cancel", 403);
        var cancelled = action(memberBrowser, memberToken, waiting, "cancel", 200);
        assertEquals("CANCELLED", cancelled.path("status").asText()); assertFalse(cancelled.path("finishedAt").isNull());
        assertEquals(cancelled, action(memberBrowser, memberToken, waiting, "cancel", 200));
        var next = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Next");
        var approved = action(ownerBrowser, null, next, "approve", 200);
        action(memberBrowser, memberToken, approved, "cancel", 409);
    }

    @Test void approveCannotAddressAnotherMeetingsRequestEvenForSameOwner() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        body(send(ownerBrowser, "PATCH", base(otherMeetingId) + "/speaking/requests/" + waiting.path("id").asLong() + "/approve", version(waiting), null, true), 404);
        assertEquals("WAITING", list(memberBrowser, memberToken).path("content").get(0).path("status").asText());
    }

    @Test void concurrentApproveAndCancelCannotProduceImpossibleTimeline() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        var results = race(() -> send(ownerBrowser, "PATCH", speaking("/requests/" + waiting.path("id").asLong() + "/approve"), version(waiting), null, true),
            () -> send(memberBrowser, "PATCH", speaking("/requests/" + waiting.path("id").asLong() + "/cancel"), version(waiting), memberToken, true));
        assertEquals(1, results.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(1, results.stream().filter(r -> r.statusCode() == 409).count());
        var stored = list(memberBrowser, memberToken).path("content").get(0);
        assertEquals(1, stored.path("version").asLong());
        assertTrue(List.of("SPEAKING", "CANCELLED").contains(stored.path("status").asText()));
    }

    @Test void endClosesPendingAndSpeakingEntriesAndKeepsHistoryReadable() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting");
        var speaking = action(ownerBrowser, null, ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaking"), "approve", 200);
        body(send(ownerBrowser, "POST", base(meetingId) + "/end", "{}", null, true), 204);
        var history = list(ownerBrowser, null);
        assertEquals(2, history.path("content").size());
        for (var entry : history.path("content")) {
            assertTrue(List.of("FINISHED", "CANCELLED").contains(entry.path("status").asText())); assertFalse(entry.path("finishedAt").isNull());
            assertFalse(entry.path("canApprove").asBoolean()); assertFalse(entry.path("canFinish").asBoolean());
        }
        assertTrue(context(ownerBrowser, null).path("readOnly").asBoolean());
        body(send(guestBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Late"), guestToken, true), 409);
        action(ownerBrowser, null, waiting, "approve", 409);
        setConnectionStatus(guestToken, "LEFT");
        assertEquals(2, list(guestBrowser, guestToken).path("content").size());
        verify(capture).finalizeForTerminalTransition(meetingId); verify(liveKit).deleteRoom(anyString());
    }

    @Test void requestRacingEndNeverLeavesLiveEntriesAfterTerminalCommit() throws Exception {
        var results = race(() -> send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Racing"), memberToken, true),
            () -> send(ownerBrowser, "POST", base(meetingId) + "/end", "{}", null, true));
        assertTrue(List.of(201, 409).contains(results.get(0).statusCode()), results.get(0).body()); assertEquals(204, results.get(1).statusCode());
        for (var entry : list(ownerBrowser, null).path("content")) assertEquals("CANCELLED", entry.path("status").asText());
        assertTrue(context(ownerBrowser, null).path("readOnly").asBoolean());
    }

    @Test void scheduledExpiryClosesEntriesAndPreservesReadOnlyHistory() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting");
        tx.executeWithoutResult(status -> {
            var stored = em.find(MeetingEntity.class, meetingId); stored.setEndTime(LocalDateTime.now().minusMinutes(1));
        });
        assertTrue(context(ownerBrowser, null).path("readOnly").asBoolean());
        assertEquals("CANCELLED", list(ownerBrowser, null).path("content").get(0).path("status").asText());
        action(ownerBrowser, null, waiting, "approve", 409); verifyNoInteractions(liveKit, capture);
    }

    @Test void changingPointRosterAfterAnyJoinFailsAndKeepsStoredPointIds() throws Exception {
        var detail = body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200);
        var input = Map.of("title", detail.path("title").asText(), "startTime", detail.path("startTime").asText(),
            "endTime", detail.path("endTime").asText(), "accessType", "PUBLIC", "pointNames", List.of("Replacement"));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 409);
        var metadata = body(send(guestBrowser, "GET", base(meetingId) + "/join-info", null, null, false), 200);
        assertEquals(List.of("School A", "School B"), strings(metadata.path("points"), "name"));
        assertEquals(pointA, metadata.path("points").get(0).path("id").asLong()); verifyNoInteractions(liveKit, capture);
    }

    @Test void deletingPointMeetingWithConnectionsAndActiveHistoryCascadesWithoutForeignKeyFailure() throws Exception {
        ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting");
        action(ownerBrowser, null, ask(guestBrowser, guestToken, UUID.randomUUID().toString(), "Speaking"), "approve", 200);
        body(send(ownerBrowser, "DELETE", base(meetingId), null, null, true), 204);
        assertEquals(0, meetingCount("meeting_speaking_requests", meetingId));
        assertEquals(0, meetingCount("meeting_points", meetingId));
        assertEquals(0, meetingCount("meeting_participant_connections", meetingId));
        body(send(guestBrowser, "GET", base(meetingId) + "/join-info", null, null, false), 404);
        verify(liveKit).deleteRoom(anyString());
    }

    @Test void editablePointRosterBeforeJoinReplacesNamesInOrderWithoutUniqueConstraintFailure() throws Exception {
        var detail = body(send(ownerBrowser, "GET", base(otherMeetingId), null, null, false), 200);
        var payload = Map.of("title", detail.path("title").asText(), "startTime", detail.path("startTime").asText(),
            "endTime", detail.path("endTime").asText(), "accessType", "PUBLIC", "pointNames", List.of("Other school", "New school"));
        body(send(ownerBrowser, "PUT", base(otherMeetingId), json.writeValueAsString(payload), null, true), 200);
        var info = body(send(guestBrowser, "GET", base(otherMeetingId) + "/join-info", null, null, false), 200);
        assertEquals(List.of("Other school", "New school"), strings(info.path("points"), "name"));
        verifyNoInteractions(liveKit, capture);
    }

    @Test void atomicPointRosterRoleSwapNeverRequiresAnIntermediateDuplicateOfficer() throws Exception {
        var chairEntry = participantEntry(chairman); var secretaryEntry = participantEntry(secretary);
        var input = updatePayload("Atomic role swap");
        input.put("participants", List.of(rosterItem(chairEntry, "SECRETARY"), rosterItem(secretaryEntry, "CHAIRMAN")));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 200);
        assertEquals("SECRETARY", participantEntry(chairman).path("role").asText()); assertEquals("CHAIRMAN", participantEntry(secretary).path("role").asText());
        assertEquals("Atomic role swap", body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200).path("title").asText());
        assertEquals(pointA, context(memberBrowser, memberToken).path("pointId").asLong()); verifyNoInteractions(liveKit, capture);
    }

    @Test void invalidAtomicPointRosterRollsBackMetadataRolesAndNewParticipantsTogether() throws Exception {
        var original = body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200);
        var input = updatePayload("Must not persist");
        input.put("participants", List.of(rosterItem(participantEntry(secretary), "SECRETARY"), Map.of("userPublicId", outsider.getPublicId(), "role", "SECRETARY")));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 400);
        var unchanged = body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200);
        assertEquals(original.path("title"), unchanged.path("title")); assertEquals(original.path("participants"), unchanged.path("participants"));
        assertTrue(context(secretaryBrowser, null).path("canModerate").asBoolean()); verifyNoInteractions(liveKit, capture);
    }

    @Test void invalidRosterRollsBackPointReplacementEvenAfterOldPointDeletionWasFlushed() throws Exception {
        var original = body(send(ownerBrowser, "GET", base(otherMeetingId), null, null, false), 200);
        var input = new LinkedHashMap<String, Object>(); input.put("title", "Must roll back point replacement");
        input.put("startTime", original.path("startTime").asText()); input.put("endTime", original.path("endTime").asText()); input.put("accessType", "PUBLIC");
        input.put("pointNames", List.of("Replacement A", "Replacement B"));
        input.put("participants", List.of(Map.of("userPublicId", chairman.getPublicId(), "role", "CHAIRMAN"), Map.of("userPublicId", outsider.getPublicId(), "role", "CHAIRMAN")));
        body(send(ownerBrowser, "PUT", base(otherMeetingId), json.writeValueAsString(input), null, true), 400);
        var unchanged = body(send(ownerBrowser, "GET", base(otherMeetingId), null, null, false), 200);
        assertEquals(original.path("title"), unchanged.path("title")); assertEquals(original.path("participants"), unchanged.path("participants"));
        var points = body(send(guestBrowser, "GET", base(otherMeetingId) + "/join-info", null, null, false), 200).path("points");
        assertEquals(1, points.size()); assertEquals(otherPoint, points.get(0).path("id").asLong()); assertEquals("Other school", points.get(0).path("name").asText());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void omittedPointOfficersLoseModerationWhileAttendeesConnectionsAndHistoryRemain() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        var input = updatePayload("Updated point roster"); input.put("participants", List.of(rosterItem(participantEntry(member), "CHAIRMAN")));
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 200);
        assertFalse(context(chairmanBrowser, null).path("canModerate").asBoolean()); assertFalse(context(secretaryBrowser, null).path("canModerate").asBoolean());
        assertTrue(context(memberBrowser, memberToken).path("canModerate").asBoolean());
        assertEquals("MEMBER", participantEntry(chairman).path("role").asText()); assertEquals("MEMBER", participantEntry(secretary).path("role").asText());
        assertEquals(pointA, context(memberBrowser, memberToken).path("pointId").asLong());
        assertEquals(waiting.path("id"), list(guestBrowser, guestToken).path("content").get(0).path("id"));
        action(secretaryBrowser, null, waiting, "approve", 403); action(memberBrowser, memberToken, waiting, "approve", 200);
        verifyNoInteractions(liveKit, capture);
    }

    @Test void standalonePointRoleMutationsCannotIntroduceSecondChairOrSecretary() throws Exception {
        var memberEntry = participantEntry(member);
        String path = base(meetingId) + "/participants/" + memberEntry.path("participantKey").asText();
        body(send(ownerBrowser, "PUT", path + "/role", "{\"role\":\"SECRETARY\"}", null, true), 400);
        body(send(ownerBrowser, "PUT", path, json.writeValueAsString(Map.of("fullName", "Must not change", "email", member.getNormalizedEmail(), "role", "CHAIRMAN")), null, true), 400);
        body(send(ownerBrowser, "POST", base(meetingId) + "/participants", json.writeValueAsString(Map.of("userPublicId", outsider.getPublicId(), "role", "SECRETARY")), null, true), 400);
        assertEquals(memberEntry, participantEntry(member)); assertFalse(context(memberBrowser, memberToken).path("canModerate").asBoolean());
        verifyNoInteractions(liveKit, capture);
    }

    @Test void removingPointAttendeeClosesTheirActiveEntriesAndRevokesTheirConnectionProof() throws Exception {
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting speaker");
        var speaking = action(ownerBrowser, null, ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaking speaker"), "approve", 200);
        var entry = participantEntry(member);
        body(send(ownerBrowser, "DELETE", base(meetingId) + "/participants/" + entry.path("participantKey").asText(), null, null, true), 204);
        var history = list(ownerBrowser, null);
        assertEquals(2, history.path("content").size());
        var statuses = strings(history.path("content"), "status"); assertTrue(statuses.contains("CANCELLED")); assertTrue(statuses.contains("FINISHED"));
        body(send(memberBrowser, "GET", speaking("/context"), null, memberToken, false), 403);
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "Removed"), memberToken, true), 403);
        verify(liveKit).removeParticipantsByAttendeeId(anyString(), eq("account:" + member.getPublicId())); verifyNoInteractions(capture);
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1&size=20", "page=0&size=0", "page=0&size=51", "page=2147483647&size=50"})
    void paginationRejectsOutOfRangeInputs(String query) throws Exception {
        body(send(memberBrowser, "GET", speaking("/requests?" + query), null, memberToken, false), 400);
    }

    @Test void invalidRequestIdsLongNamesAndNegativeVersionsFailWithoutChangingData() throws Exception {
        for (String id : List.of("", "not-a-uuid", "x".repeat(100))) body(send(memberBrowser, "POST", speaking("/requests"), payload(id, "Speaker"), memberToken, true), 400);
        body(send(memberBrowser, "POST", speaking("/requests"), payload(UUID.randomUUID().toString(), "x".repeat(256)), memberToken, true), 400);
        assertEquals(0, count("meeting_speaking_requests"));
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        body(send(ownerBrowser, "PATCH", speaking("/requests/" + waiting.path("id").asLong() + "/approve"), "{\"version\":-1}", null, true), 400);
        assertEquals("WAITING", list(memberBrowser, memberToken).path("content").get(0).path("status").asText());
    }

    @Test void malformedSpeakingPayloadsAndTypedParametersReturnSanitizedClientErrors() throws Exception {
        for (String payload : List.of("{", "{\"requestId\":{\"unexpected\":true}}", "{\"requestId\":null}")) {
            var failure = body(send(memberBrowser, "POST", speaking("/requests"), payload, memberToken, true), 400);
            assertFalse(failure.path("message").asText().contains("com.fasterxml")); assertFalse(failure.path("message").asText().contains("vn.cusc"));
        }
        assertEquals(0, count("meeting_speaking_requests"));
        var waiting = ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Speaker");
        body(send(ownerBrowser, "PATCH", speaking("/requests/" + waiting.path("id").asLong() + "/approve"), "{\"version\":\"bad\"}", null, true), 400);
        body(send(memberBrowser, "GET", speaking("/requests?page=bad"), null, memberToken, false), 400);
        body(send(ownerBrowser, "PATCH", speaking("/requests/bad/approve"), version(waiting), null, true), 400);
        assertEquals("WAITING", list(memberBrowser, memberToken).path("content").get(0).path("status").asText());
    }

    @Test void validUnicodeNamesAtBoundarySurviveLowercaseKeyExpansionWithoutDatabaseErrors() throws Exception {
        String pointName = "İ".repeat(150), speakerName = "İ".repeat(255);
        long created = createPoint(List.of(pointName), false);
        var metadata = body(send(guestBrowser, "GET", base(created) + "/join-info", null, null, false), 200);
        assertEquals(pointName, metadata.path("points").get(0).path("name").asText());
        assertEquals(speakerName, ask(memberBrowser, memberToken, UUID.randomUUID().toString(), speakerName).path("speakerName").asText());
    }

    private record Browser(HttpClient client, JsonNode csrf) { }
    private Browser browser() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        var bare = new Browser(client, null); return new Browser(client, body(send(bare, "GET", "/api/auth/csrf", null, null, false), 200));
    }
    private Browser signedIn(UserAccountEntity account) throws Exception {
        var caller = browser(); body(send(caller, "POST", "/api/auth/login", json.writeValueAsString(Map.of("usernameOrEmail", account.getUsername(), "password", PASSWORD)), null, true), 200);
        return new Browser(caller.client(), body(send(caller, "GET", "/api/auth/csrf", null, null, false), 200));
    }
    private long createPoint(List<String> names, boolean inviteModerators) throws Exception {
        return body(send(ownerBrowser, "POST", "/api/meetings", pointCreatePayload(names, inviteModerators, UUID.randomUUID().toString()), null, true), 201).path("id").asLong();
    }
    private String pointCreatePayload(List<String> names, boolean inviteModerators, String clientRequestId) throws Exception {
        var payload = new LinkedHashMap<String, Object>(); payload.put("title", "Point test " + UUID.randomUUID()); payload.put("description", "Public point meeting");
        payload.put("creationMode", "POINT"); payload.put("accessType", "PUBLIC"); payload.put("startTime", LocalDateTime.now().minusHours(1).withNano(0).toString());
        payload.put("endTime", LocalDateTime.now().plusHours(2).withNano(0).toString()); payload.put("pointNames", names);
        payload.put("clientRequestId", clientRequestId);
        payload.put("participants", inviteModerators ? List.of(Map.of("userPublicId", chairman.getPublicId(), "role", "CHAIRMAN"), Map.of("userPublicId", secretary.getPublicId(), "role", "SECRETARY")) : List.of());
        return json.writeValueAsString(payload);
    }
    private JsonNode join(Browser browser, long point, String type, String name) throws Exception {
        return body(send(browser, "POST", base(meetingId) + "/join", json.writeValueAsString(Map.of("participantType", type, "displayName", name, "pointId", point)), null, true), 200);
    }
    private JsonNode context(Browser browser, String token) throws Exception { return body(send(browser, "GET", speaking("/context"), null, token, false), 200); }
    private JsonNode list(Browser browser, String token) throws Exception { return body(send(browser, "GET", speaking("/requests?page=0&size=50"), null, token, false), 200); }
    private JsonNode ask(Browser browser, String token, String id, String name) throws Exception { return body(send(browser, "POST", speaking("/requests"), payload(id, name), token, true), 201); }
    private JsonNode action(Browser browser, String token, JsonNode item, String action, int expected) throws Exception {
        return body(send(browser, "PATCH", speaking("/requests/" + item.path("id").asLong() + "/" + action), version(item), token, true), expected);
    }
    private String payload(String id, String name) throws Exception { return json.writeValueAsString(Map.of("requestId", id, "speakerName", name)); }
    private String guestResumePayload(String id, String proof, long point) throws Exception {
        var input = new LinkedHashMap<String, Object>(); input.put("participantType", "GUEST"); input.put("displayName", "Shared room representative");
        input.put("guestId", id); input.put("pointId", point); if (proof != null) input.put("guestResumeToken", proof);
        return json.writeValueAsString(input);
    }
    private Map<String, Object> updatePayload(String title) throws Exception {
        var original = body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200);
        var input = new LinkedHashMap<String, Object>(); input.put("title", title); input.put("startTime", original.path("startTime").asText());
        input.put("endTime", original.path("endTime").asText()); input.put("accessType", "PUBLIC"); return input;
    }
    private JsonNode participantEntry(UserAccountEntity account) throws Exception {
        var roster = body(send(ownerBrowser, "GET", base(meetingId) + "/participants", null, null, false), 200);
        for (var value : roster) if (value.path("userPublicId").asText().equals(account.getPublicId())) return value;
        fail("Account participant not found"); return json.nullNode();
    }
    private Map<String, Object> rosterItem(JsonNode participant, String role) {
        return Map.of("participantKey", participant.path("participantKey").asText(), "userPublicId", participant.path("userPublicId").asText(), "role", role);
    }
    private String version(JsonNode item) throws Exception { return json.writeValueAsString(Map.of("version", item.path("version").asLong())); }
    private String base(long id) { return "/api/meetings/" + id; }
    private String speaking(String suffix) { return base(meetingId) + "/speaking" + suffix; }
    private HttpResponse<String> send(Browser browser, String method, String path, String payload, String token, boolean withCsrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").header("Content-Type", "application/json");
        if (withCsrf && browser.csrf() != null) builder.header(browser.csrf().path("headerName").asText(), browser.csrf().path("token").asText());
        if (token != null) builder.header("X-Meeting-Participant-Token", token);
        return browser.client().send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body()); return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }
    private List<String> strings(JsonNode array, String field) { var result = new ArrayList<String>(); array.forEach(value -> result.add(value.path(field).asText())); return result; }
    private UserAccountEntity account(String name) {
        var value = new UserAccountEntity(); String key = UUID.randomUUID().toString(); value.setPublicId(key); value.setUsername(key); value.setNormalizedEmail(key + "@example.test");
        value.setFullName(name); value.setAccountStatus(AccountStatus.ACTIVE); value.setSystemRole(SystemRole.USER); value.setPasswordHash(passwordHash); value.setEmailVerified(true); em.persist(value); return value;
    }
    private long count(String table) {
        assertTrue(Set.of("meetings", "meeting_participant_connections", "meeting_speaking_requests").contains(table));
        return tx.execute(status -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }
    private long meetingCount(String table, long id) {
        assertTrue(Set.of("meeting_participant_connections", "meeting_speaking_requests", "meeting_points").contains(table));
        return tx.execute(status -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE meeting_id=:meeting").setParameter("meeting", id).getSingleResult()).longValue());
    }
    private void setConnectionStatus(String token, String value) {
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE meeting_participant_connections SET status=:state WHERE chat_token_hash=:hash")
            .setParameter("state", value).setParameter("hash", MeetingChatTokenUtils.sha256(token)).executeUpdate());
    }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown(); return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    private static String url() { return System.getProperty("qna.postgres.url"); }
    private static String username() { return System.getProperty("qna.postgres.username", "qna_test"); }
    private static String password() { return Objects.requireNonNull(System.getenv("QNA_TEST_DB_PASSWORD")); }
}
