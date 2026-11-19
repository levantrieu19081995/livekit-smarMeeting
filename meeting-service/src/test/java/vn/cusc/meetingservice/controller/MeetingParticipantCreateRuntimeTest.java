package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.MeetingCaptureService;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

/** Real session/CSRF/controllers/services/PostgreSQL; no external meeting rooms or capture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingParticipantCreateRuntimeTest {
    private static final String SCHEMA = "qna_p2_create_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "Isolated-Create-Test-2026!";
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
    private TransactionTemplate tx;
    private UserAccountEntity owner;
    private List<UserAccountEntity> invitees;
    private HttpClient manager;
    private JsonNode managerCsrf;
    private int terminalTransitions;
    private final LocalDateTime start = LocalDateTime.now().plusDays(2).withNano(0);

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", MeetingParticipantCreateRuntimeTest::username);
        properties.add("spring.datasource.password", MeetingParticipantCreateRuntimeTest::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() throws Exception {
        terminalTransitions = 0;
        tx = new TransactionTemplate(transactions);
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        tx.executeWithoutResult(status -> {
            owner = account(SystemRole.MEETING_MANAGER);
            invitees = List.of(account(SystemRole.USER), account(SystemRole.USER), account(SystemRole.USER), account(SystemRole.USER));
        });
        when(liveKit.createRoom(anyString())).thenAnswer(invocation -> new LiveKitRoomResponse(invocation.getArgument(0), "RM_test_create", 0, 0));
        manager = client();
        managerCsrf = signIn(manager, owner);
        assertEquals(0, count("meetings"));
    }

    @AfterEach
    void removeOnlyFixtureMeetings() {
        verifyNoInteractions(stt);
        if (terminalTransitions > 0) verify(capture, times(terminalTransitions)).finalizeForTerminalTransition(anyLong());
        else verifyNoInteractions(capture);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM meetings").executeUpdate());
    }

    @AfterAll
    static void dropOnlyOwnedSchema() throws Exception {
        if (!schemaCreated) return;
        assertTrue(SCHEMA.matches("qna_p2_create_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void chairSecretaryAndTwoMembersPersistTheirExactRolesAndAccountLinks(MeetingAccessType access) throws Exception {
        List<Map<String, String>> participants = List.of(invite(0, "CHAIRMAN"), invite(1, "SECRETARY"), invite(2, "MEMBER"), invite(3, "MEMBER"));
        JsonNode created = body(request(manager, "POST", "/api/meetings", createJson(access, participants), managerCsrf), 201);
        long id = created.path("id").asLong();
        assertTrue(id > 0);
        assertEquals(access.name(), created.path("accessType").asText());
        assertEquals("RM_test_create", created.path("roomSid").asText());
        verify(liveKit, times(1)).createRoom(created.path("roomName").asText());
        verifyNoMoreInteractions(liveKit);
        tx.executeWithoutResult(status -> {
            MeetingEntity stored = em.find(MeetingEntity.class, id);
            assertEquals(owner.getId(), stored.getCreatedByAccount().getId());
            assertEquals(access == MeetingAccessType.PRIVATE ? 5 : 4, stored.getParticipants().size());
            if (access == MeetingAccessType.PRIVATE) {
                var creator = stored.getParticipants().stream()
                    .filter(p -> p.getUserAccount().getId().equals(owner.getId())).findFirst().orElseThrow();
                assertEquals(MeetingRole.MEMBER, creator.getRole());
            }
            for (int index = 0; index < participants.size(); index++) {
                var expectedAccount = invitees.get(index);
                var actual = stored.getParticipants().stream().filter(p -> p.getUserAccount().getId().equals(expectedAccount.getId())).findFirst().orElseThrow();
                assertEquals(MeetingRole.valueOf(participants.get(index).get("role")), actual.getRole());
                assertEquals(ParticipantStatus.INVITED, actual.getStatus());
                assertEquals(ParticipantJoinType.AUTHENTICATED, actual.getJoinType());
                assertEquals(expectedAccount.getNormalizedEmail(), actual.getEmail());
            }
        });
        JsonNode detail = body(request(manager, "GET", "/api/meetings/" + id, null, null), 200);
        assertEquals(access == MeetingAccessType.PRIVATE ? 5 : 4, detail.path("participants").size());
        assertEquals(access == MeetingAccessType.PRIVATE ? 3 : 2, roles(detail.path("participants"), "MEMBER"));
        assertEquals(1, roles(detail.path("participants"), "SECRETARY"));
        assertEquals(1, roles(detail.path("participants"), "CHAIRMAN"));
        assertEquals(0, count("meeting_participant_connections"));
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void duplicateSecretaryRejectsBeforeExternalRoomOrAnyDomainRows(MeetingAccessType access) throws Exception {
        String payload = createJson(access, List.of(invite(0, "CHAIRMAN"), invite(1, "SECRETARY"), invite(2, "SECRETARY")));
        JsonNode rejected = body(request(manager, "POST", "/api/meetings", payload, managerCsrf), 400);
        assertEquals("BUSINESS_VALIDATION_ERROR", rejected.path("code").asText());
        assertEquals("Chỉ được có 1 thư ký trong cuộc họp.", rejected.path("message").asText());
        assertNoCreation();
    }

    @Test
    void convertingPublicToPrivateInvitesCreatorWithoutDuplicatingChairmanOrSecretary() throws Exception {
        var created = body(request(manager, "POST", "/api/meetings", createJson(MeetingAccessType.PUBLIC,
            List.of(invite(0, "CHAIRMAN"), invite(1, "SECRETARY"))), managerCsrf), 201);
        long id = created.path("id").asLong();
        var payload = json.writeValueAsString(Map.of("title", created.path("title").asText(),
            "startTime", created.path("startTime").asText(), "endTime", created.path("endTime").asText(),
            "accessType", "PRIVATE"));
        body(request(manager, "PUT", "/api/meetings/" + id, payload, managerCsrf), 200);
        var detail = body(request(manager, "GET", "/api/meetings/" + id, null, null), 200);
        assertEquals(3, detail.path("participants").size());
        assertEquals(1, roles(detail.path("participants"), "MEMBER"));
        assertEquals(1, roles(detail.path("participants"), "CHAIRMAN"));
        assertEquals(1, roles(detail.path("participants"), "SECRETARY"));
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void noSecretaryIsAllowedAndMembersStayMembers(MeetingAccessType access) throws Exception {
        JsonNode created = body(request(manager, "POST", "/api/meetings", createJson(access, List.of(invite(0, "CHAIRMAN"), invite(1, "MEMBER"), invite(2, "MEMBER"))), managerCsrf), 201);
        JsonNode detail = body(request(manager, "GET", "/api/meetings/" + created.path("id").asLong(), null, null), 200);
        assertEquals(0, roles(detail.path("participants"), "SECRETARY"));
        assertEquals(access == MeetingAccessType.PRIVATE ? 3 : 2, roles(detail.path("participants"), "MEMBER"));
        assertEquals(access == MeetingAccessType.PRIVATE ? 4 : 3, count("meeting_participants"));
        verify(liveKit, times(1)).createRoom(anyString());
        verifyNoMoreInteractions(liveKit);
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void existingOneChairmanRuleStillRejectsTwoChairmen(MeetingAccessType access) throws Exception {
        JsonNode rejected = body(request(manager, "POST", "/api/meetings", createJson(access, List.of(invite(0, "CHAIRMAN"), invite(1, "CHAIRMAN"), invite(2, "MEMBER"))), managerCsrf), 400);
        assertEquals("BUSINESS_VALIDATION_ERROR", rejected.path("code").asText());
        assertEquals("Chi duoc co 1 chairman", rejected.path("message").asText());
        assertNoCreation();
    }

    @Test
    void anonymousCreateWithValidCsrfStillNeedsLogin() throws Exception {
        HttpClient anonymous = client();
        body(request(anonymous, "POST", "/api/meetings", createJson(MeetingAccessType.PUBLIC, List.of(invite(0, "MEMBER"))), csrf(anonymous)), 401);
        assertNoCreation();
    }

    @Test
    void ordinaryUserCreatesAndOwnsMeetingWithValidSessionAndCsrf() throws Exception {
        HttpClient ordinary = client();
        JsonNode token = signIn(ordinary, invitees.get(0));
        var created = body(request(ordinary, "POST", "/api/meetings", createJson(MeetingAccessType.PUBLIC, List.of(invite(0, "MEMBER"))), token), 201);
        assertTrue(created.path("canManage").asBoolean());
        tx.executeWithoutResult(status -> assertEquals(invitees.get(0).getId(),
            em.find(MeetingEntity.class, created.path("id").asLong()).getCreatedByAccount().getId()));
        verify(liveKit).createRoom(anyString());
    }

    @Test
    void loggedInManagerCannotCreateWithoutCsrf() throws Exception {
        body(request(manager, "POST", "/api/meetings", createJson(MeetingAccessType.PUBLIC, List.of(invite(0, "MEMBER"))), null), 403);
        assertNoCreation();
    }

    @Test
    void publicMeetingCanStillStartWithoutInvitees() throws Exception {
        body(request(manager, "POST", "/api/meetings", createJson(MeetingAccessType.PUBLIC, List.of()), managerCsrf), 201);
        assertEquals(1, count("meetings"));
        assertEquals(0, count("meeting_participants"));
        verify(liveKit, times(1)).createRoom(anyString());
        verifyNoMoreInteractions(liveKit);
    }

    @Test
    void privateMeetingStillRequiresInvitees() throws Exception {
        JsonNode rejected = body(request(manager, "POST", "/api/meetings", createJson(MeetingAccessType.PRIVATE, List.of()), managerCsrf), 400);
        assertEquals("BUSINESS_VALIDATION_ERROR", rejected.path("code").asText());
        assertEquals("Meeting private phai co participant", rejected.path("message").asText());
        assertNoCreation();
    }

    @Test
    void editingLegacyMultipleSecretariesAndAddingMemberStillWorks() throws Exception {
        long id = tx.execute(status -> {
            MeetingEntity legacy = seedMeeting();
            seedParticipant(legacy, invitees.get(0), MeetingRole.CHAIRMAN);
            seedParticipant(legacy, invitees.get(1), MeetingRole.SECRETARY);
            seedParticipant(legacy, invitees.get(2), MeetingRole.SECRETARY);
            return legacy.getId();
        });
        body(request(manager, "PUT", "/api/meetings/" + id, updateJson("Legacy title edited"), managerCsrf), 200);
        JsonNode added = body(request(manager, "POST", "/api/meetings/" + id + "/participants", json.writeValueAsString(invite(3, "MEMBER")), managerCsrf), 201);
        assertEquals("MEMBER", added.path("role").asText());
        JsonNode detail = body(request(manager, "GET", "/api/meetings/" + id, null, null), 200);
        assertEquals("Legacy title edited", detail.path("title").asText());
        assertEquals(2, roles(detail.path("participants"), "SECRETARY"));
        assertEquals(1, roles(detail.path("participants"), "MEMBER"));
        verifyNoInteractions(liveKit);
    }

    @Test
    void editingAlreadyJoinedGuestPreservesGuestIdentityAndConnectedSession() throws Exception {
        var guest = tx.execute(status -> {
            MeetingEntity legacy = seedMeeting();
            legacy.setStartTime(LocalDateTime.now().minusHours(1));
            legacy.setEndTime(LocalDateTime.now().plusHours(1));
            legacy.setStatus(MeetingStatus.IN_PROGRESS);
            seedParticipant(legacy, invitees.get(0), MeetingRole.CHAIRMAN);
            MeetingParticipantEntity participant = seedParticipant(legacy, null, MeetingRole.GUEST);
            var connection = new MeetingParticipantConnectionEntity();
            connection.setMeeting(legacy); connection.setParticipant(participant);
            connection.setAttendeeId("guest:" + participant.getParticipantKey());
            connection.setConnectionId(UUID.randomUUID().toString());
            connection.setLiveKitIdentity(connection.getAttendeeId() + ":" + connection.getConnectionId());
            connection.setStatus(ParticipantConnectionStatus.CONNECTED); connection.setJoinedAt(LocalDateTime.now());
            connection.setChatTokenHash(MeetingChatTokenUtils.sha256("owned-test-guest-token"));
            em.persist(connection);
            return participant;
        });
        long id = guest.getMeeting().getId();
        String originalUserId = guest.getUserId();
        String originalKey = guest.getParticipantKey();
        JsonNode response = body(request(manager, "PUT", "/api/meetings/" + id + "/participants/" + originalKey, json.writeValueAsString(Map.of("fullName", "Guest edited", "email", "edited-guest@example.test", "role", "GUEST")), managerCsrf), 200);
        assertEquals("GUEST", response.path("role").asText());
        JsonNode roleResponse = body(request(manager, "PUT", "/api/meetings/" + id + "/participants/" + originalKey + "/role", "{\"role\":\"GUEST\"}", managerCsrf), 200);
        assertEquals("GUEST", roleResponse.path("role").asText());
        tx.executeWithoutResult(status -> {
            MeetingParticipantEntity stored = em.find(MeetingParticipantEntity.class, guest.getId());
            assertNull(stored.getUserAccount());
            assertEquals(originalUserId, stored.getUserId());
            assertEquals(originalKey, stored.getParticipantKey());
            assertEquals(ParticipantStatus.JOINED, stored.getStatus());
            assertEquals(ParticipantJoinType.GUEST, stored.getJoinType());
            assertEquals("Guest edited", stored.getFullName());
            assertEquals("edited-guest@example.test", stored.getEmail());
            var connection = em.createQuery("SELECT c FROM MeetingParticipantConnectionEntity c WHERE c.participant.id=:participant", MeetingParticipantConnectionEntity.class).setParameter("participant", guest.getId()).getSingleResult();
            assertEquals(ParticipantConnectionStatus.CONNECTED, connection.getStatus());
            assertEquals("guest:" + originalKey, connection.getAttendeeId());
            assertEquals(MeetingChatTokenUtils.sha256("owned-test-guest-token"), connection.getChatTokenHash());
        });
        verifyNoInteractions(liveKit);
    }


    @ParameterizedTest
    @EnumSource(value = MeetingCreationMode.class, names = {"INSTANT", "LATER"})
    void datelessCreationPersistsNullEndAndOnlyCreatorMayEditOrDelete(MeetingCreationMode mode) throws Exception {
        HttpClient creatorClient = client(); var creatorCsrf = signIn(creatorClient, invitees.get(0));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        var created = body(request(creatorClient, "POST", "/api/meetings", json.writeValueAsString(Map.of(
            "creationMode", mode.name(), "accessType", "PUBLIC", "description", "Dateless runtime")), creatorCsrf), 201);
        long id = created.path("id").asLong();
        assertTrue(created.path("canManage").asBoolean()); assertTrue(created.path("endTime").isNull());
        assertEquals(mode.name(), created.path("creationMode").asText());
        tx.executeWithoutResult(status -> {
            var stored = em.find(MeetingEntity.class, id); assertNull(stored.getEndTime());
            assertEquals(invitees.get(0).getId(), stored.getCreatedByAccount().getId());
            if (mode == MeetingCreationMode.LATER) assertNull(stored.getStartTime());
            else assertTrue(!stored.getStartTime().isBefore(before) && !stored.getStartTime().isAfter(LocalDateTime.now()));
        });
        HttpClient viewer = client(); var viewerCsrf = signIn(viewer, invitees.get(1));
        assertFalse(body(request(viewer, "GET", "/api/meetings/" + id, null, null), 200).path("canManage").asBoolean());
        var edit = json.writeValueAsString(Map.of("title", "Updated link", "description", "Edited", "accessType", "PUBLIC"));
        body(request(viewer, "PUT", "/api/meetings/" + id, edit, viewerCsrf), 403);
        body(request(viewer, "DELETE", "/api/meetings/" + id, null, viewerCsrf), 403);
        var updated = body(request(creatorClient, "PUT", "/api/meetings/" + id, edit, creatorCsrf), 200);
        assertEquals("Updated link", updated.path("title").asText()); assertTrue(updated.path("endTime").isNull());
        assertEquals(0, body(request(viewer, "GET", "/api/minutes/summary", null, null), 200).path("totalMeetings").asInt());
        terminalTransitions = 2;
        assertEquals(204, request(creatorClient, "POST", "/api/meetings/" + id + "/end", null, creatorCsrf).statusCode());
        assertEquals(1, body(request(viewer, "GET", "/api/minutes/summary", null, null), 200).path("totalMeetings").asInt());
        assertEquals(204, request(creatorClient, "DELETE", "/api/meetings/" + id, null, creatorCsrf).statusCode());
        assertEquals(0, count("meetings"));
    }

    @Test
    void laterLinkStartsOnFirstJoinAndGuestsCanCreateVoteAndClosePoll() throws Exception {
        var created = body(request(manager, "POST", "/api/meetings", json.writeValueAsString(Map.of("creationMode", "LATER", "accessType", "PUBLIC", "title", "Guest poll runtime")), managerCsrf), 201);
        long id = created.path("id").asLong(); var base = "/api/meetings/" + id;
        when(liveKit.ensureRoomExists(anyString())).thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_guest_poll", 0, 0));
        when(liveKit.generateJoinToken(anyString(), anyString(), anyString(), anyMap(), eq(true), eq(true))).thenReturn("isolated-livekit-token");
        HttpClient guestClient = client(); var guestCsrf = csrf(guestClient);
        var joined = body(request(guestClient, "POST", base + "/join", json.writeValueAsString(Map.of("participantType", "GUEST", "displayName", "First guest")), guestCsrf), 200);
        var guestToken = joined.path("participant").path("chatWriteToken").asText(); assertFalse(guestToken.isBlank());
        var activeMeeting = body(request(manager, "GET", base, null, null), 200);
        assertEquals("IN_PROGRESS", activeMeeting.path("status").asText(), activeMeeting.toString()); assertFalse(activeMeeting.path("startTime").isNull()); assertTrue(activeMeeting.path("endTime").isNull());
        var pollRoute = base + "/polls";
        var pollJson = json.writeValueAsString(Map.of("question", "Approve guest flow?", "type", "YES_NO_ABSTAIN", "resultVisibility", "LIVE", "minSelections", 1, "maxSelections", 1, "eligibleParticipantKeys", List.of()));
        assertEquals(403, request(guestClient, "POST", pollRoute, pollJson, null, guestToken).statusCode());
        var poll = body(request(guestClient, "POST", pollRoute, pollJson, guestCsrf, guestToken), 201);
        long pollId = poll.path("id").asLong(); var route = pollRoute + "/" + pollId;
        tx.executeWithoutResult(status -> {
            var stored = em.find(vn.cusc.meetingservice.entity.MeetingPollEntity.class, pollId);
            assertNull(stored.getCreatedByAccount()); assertNotNull(stored.getCreatedByParticipant());
        });
        body(request(guestClient, "POST", route + "/open", null, guestCsrf, guestToken), 200);
        HttpClient lateClient = client(); var lateCsrf = csrf(lateClient);
        var lateJoined = body(request(lateClient, "POST", base + "/join", json.writeValueAsString(Map.of("participantType", "GUEST", "displayName", "Late guest")), lateCsrf), 200);
        var lateToken = lateJoined.path("participant").path("chatWriteToken").asText();
        var voteJson = json.writeValueAsString(Map.of("optionIds", List.of(poll.path("options").get(0).path("id").asLong())));
        var firstVote = body(request(lateClient, "PUT", route + "/my-vote", voteJson, lateCsrf, lateToken), 200);
        assertEquals(2, firstVote.path("eligibleCount").asInt()); assertEquals(1, firstVote.path("votedCount").asInt());
        assertEquals(firstVote.path("myVote"), body(request(lateClient, "PUT", route + "/my-vote", voteJson, lateCsrf, lateToken), 200).path("myVote"));
        body(request(lateClient, "POST", route + "/close", null, lateCsrf, lateToken), 200);
        assertEquals(409, request(guestClient, "PUT", route + "/my-vote", voteJson, guestCsrf, guestToken).statusCode());
        assertEquals(403, request(manager, "GET", pollRoute + "/context", null, null, "invalid").statusCode());
        assertEquals(403, request(manager, "GET", pollRoute + "/context", null, null, guestToken).statusCode());
        terminalTransitions = 1;
        assertEquals(204, request(manager, "POST", base + "/end", null, managerCsrf).statusCode());
        var finished = body(request(manager, "GET", base, null, null), 200);
        assertEquals("COMPLETED", finished.path("status").asText()); assertFalse(finished.path("endTime").isNull());
        assertEquals(409, request(guestClient, "POST", pollRoute, pollJson, guestCsrf, guestToken).statusCode());
        assertEquals(1, count("meeting_poll_ballots"));
    }

    @ParameterizedTest
    @EnumSource(MeetingAccessType.class)
    void privateOfficersMayEditAndOrdinaryMembersOnlyView(MeetingAccessType access) throws Exception {
        var created = body(request(manager, "POST", "/api/meetings", createJson(access, List.of(invite(0, "CHAIRMAN"), invite(1, "SECRETARY"), invite(2, "MEMBER"))), managerCsrf), 201);
        long id = created.path("id").asLong();
        terminalTransitions = 1;
        assertEquals(204, request(manager, "POST", "/api/meetings/" + id + "/end", null, managerCsrf).statusCode());
        for (int index = 0; index < 4; index++) {
            HttpClient caller = client(); var token = signIn(caller, invitees.get(index));
            int expectedView = access == MeetingAccessType.PRIVATE && index == 3 ? 403 : 200;
            var viewed = body(request(caller, "GET", "/api/meetings/" + id, null, null), expectedView);
            boolean canManage = access == MeetingAccessType.PRIVATE && index < 2;
            if (expectedView == 200) assertEquals(canManage, viewed.path("canManage").asBoolean());
            var payload = json.writeValueAsString(Map.of("title", "Edit permissions", "startTime", start.toString(), "endTime", start.plusHours(1).toString(), "accessType", access.name()));
            body(request(caller, "PUT", "/api/meetings/" + id, payload, token), canManage ? 200 : 403);
            var list = body(request(caller, "GET", "/api/minutes", null, null), 200);
            assertEquals(expectedView == 200 ? 1 : 0, list.path("totalElements").asInt());
            if (expectedView == 200) assertEquals(canManage, list.path("content").get(0).path("canManage").asBoolean());
        }
    }

    private void assertNoCreation() {
        for (String table : List.of("meetings", "meeting_participants", "meeting_participant_connections", "meeting_qa_questions", "meeting_polls", "meeting_minutes")) assertEquals(0, count(table), table);
        verifyNoInteractions(liveKit);
    }

    private long count(String table) {
        assertTrue(table.matches("[a-z_]+"));
        return tx.execute(status -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    private long roles(JsonNode participants, String role) {
        long total = 0;
        for (JsonNode participant : participants) if (role.equals(participant.path("role").asText())) total++;
        return total;
    }

    private Map<String, String> invite(int index, String role) {
        return Map.of("userPublicId", invitees.get(index).getPublicId(), "role", role);
    }

    private String createJson(MeetingAccessType access, List<Map<String, String>> participants) throws Exception {
        return json.writeValueAsString(Map.of("title", "Isolated participant create", "description", "Runtime verification", "startTime", start.toString(), "endTime", start.plusHours(1).toString(), "accessType", access.name(), "participants", participants));
    }

    private String updateJson(String title) throws Exception {
        return json.writeValueAsString(Map.of("title", title, "startTime", start.toString(), "endTime", start.plusHours(1).toString(), "accessType", "PUBLIC"));
    }

    private UserAccountEntity account(SystemRole role) {
        var account = new UserAccountEntity(); String key = UUID.randomUUID().toString();
        account.setPublicId(key); account.setUsername(key); account.setNormalizedEmail(key + "@example.test");
        account.setFullName("Create test " + key); account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(role);
        account.setPasswordHash(passwordHash); account.setEmailVerified(true); em.persist(account); return account;
    }

    private MeetingEntity seedMeeting() {
        var meeting = new MeetingEntity(); String key = UUID.randomUUID().toString().replace("-", "");
        meeting.setMeetingCode(key); meeting.setRoomName(key); meeting.setTitle("Legacy meeting");
        meeting.setStartTime(start); meeting.setEndTime(start.plusHours(1)); meeting.setAccessType(MeetingAccessType.PUBLIC);
        meeting.setStatus(MeetingStatus.UPCOMING); meeting.setCreatedByAccount(em.find(UserAccountEntity.class, owner.getId()));
        em.persist(meeting); return meeting;
    }

    private MeetingParticipantEntity seedParticipant(MeetingEntity meeting, UserAccountEntity account, MeetingRole role) {
        var participant = new MeetingParticipantEntity(); String key = UUID.randomUUID().toString();
        participant.setMeeting(meeting); participant.setUserAccount(account == null ? null : em.find(UserAccountEntity.class, account.getId()));
        participant.setParticipantKey(key); participant.setUserId(account == null ? "guest-" + key : "account-" + account.getPublicId());
        participant.setFullName(account == null ? "Joined guest" : account.getFullName());
        participant.setEmail(account == null ? key + "@guest.local" : account.getNormalizedEmail());
        participant.setRole(role); participant.setStatus(ParticipantStatus.JOINED);
        participant.setJoinType(account == null ? ParticipantJoinType.GUEST : ParticipantJoinType.AUTHENTICATED);
        em.persist(participant); return participant;
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        return body(request(client, "GET", "/api/auth/csrf", null, null), 200);
    }

    private JsonNode signIn(HttpClient client, UserAccountEntity account) throws Exception {
        body(request(client, "POST", "/api/auth/login", json.writeValueAsString(Map.of("usernameOrEmail", account.getUsername(), "password", PASSWORD)), csrf(client)), 200);
        return csrf(client);
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, String payload, JsonNode csrf) throws Exception {
        return request(client, method, path, payload, csrf, null);
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, String payload, JsonNode csrf, String participantToken) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("Content-Type", "application/json");
        if (participantToken != null) builder.header("X-Meeting-Participant-Token", participantToken);
        if (csrf != null) builder.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return client.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body()); return json.readTree(response.body());
    }

    private static String url() { return System.getProperty("qna.postgres.url"); }
    private static String username() { return System.getProperty("qna.postgres.username", "qna_test"); }
    private static String password() { return Objects.requireNonNull(System.getenv("QNA_TEST_DB_PASSWORD")); }
}
