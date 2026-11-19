package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.dto.poll.MeetingPollRequest;
import vn.cusc.meetingservice.dto.poll.MeetingPollResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollVoteRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingPollConflictException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.MeetingPollBallotRepository;
import vn.cusc.meetingservice.repository.MeetingPollRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.MeetingPrincipal;

/** Real HTTP/security/session/CSRF/services and PostgreSQL, isolated from application schemas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "poll.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingPollRuntimeTest {
    private static final String SCHEMA = "poll_p7_test_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean schemaCreated;
    @LocalServerPort private int port;
    @Autowired private MeetingPollService polls;
    @Autowired private MeetingService meetings;
    @Autowired private MeetingPollRepository pollRepository;
    @Autowired private MeetingPollBallotRepository ballots;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper json;
    @MockBean private LiveKitService liveKit;
    @MockBean private MeetingCaptureService capture;
    private MeetingEntity meeting;
    private MeetingParticipantEntity member;
    private UserAccountEntity owner;
    private TransactionTemplate tx;
    private static final String PASSWORD = "Poll-test-2026!";

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", MeetingPollRuntimeTest::username);
        properties.add("spring.datasource.password", MeetingPollRuntimeTest::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            owner = account(SystemRole.MEETING_MANAGER);
            String key = UUID.randomUUID().toString().replace("-", "");
            meeting = new MeetingEntity();
            meeting.setMeetingCode(key); meeting.setRoomName(key); meeting.setTitle("Poll runtime");
            meeting.setStartTime(LocalDateTime.now().minusHours(1));
            meeting.setEndTime(LocalDateTime.now().plusHours(1));
            meeting.setStatus(MeetingStatus.IN_PROGRESS); meeting.setAccessType(MeetingAccessType.PRIVATE);
            meeting.setCreatedByAccount(owner); em.persist(meeting);
            member = new MeetingParticipantEntity();
            member.setMeeting(meeting); member.setUserAccount(account(SystemRole.USER));
            member.setParticipantKey(UUID.randomUUID().toString()); member.setUserId(member.getParticipantKey());
            member.setFullName("Voter"); member.setRole(MeetingRole.MEMBER);
            member.setEmail(member.getUserAccount().getNormalizedEmail());
            member.setStatus(ParticipantStatus.INVITED); member.setJoinType(ParticipantJoinType.AUTHENTICATED);
            em.persist(member);
            var chairman = new MeetingParticipantEntity();
            chairman.setMeeting(meeting); chairman.setUserAccount(owner);
            chairman.setParticipantKey(UUID.randomUUID().toString()); chairman.setUserId("account-" + owner.getPublicId());
            chairman.setFullName("Chairman"); chairman.setRole(MeetingRole.CHAIRMAN);
            chairman.setEmail(owner.getNormalizedEmail()); chairman.setStatus(ParticipantStatus.INVITED);
            chairman.setJoinType(ParticipantJoinType.AUTHENTICATED); em.persist(chairman);
        });
        login(owner);
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        reset(capture, liveKit);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM meetings WHERE id = :id")
            .setParameter("id", meeting.getId()).executeUpdate());
    }

    @AfterAll
    static void removeOnlyOwnedSchema() throws Exception {
        if (!schemaCreated) return;
        assertTrue(SCHEMA.matches("poll_p7_test_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(url(), username(), password());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void voteAgainstCloseHasOneConsistentCommittedOutcome() throws Exception {
        var poll = open(false);
        var outcomes = race(() -> vote(member.getUserAccount(), poll), () -> {
            login(owner); polls.close(meeting.getId(), poll.id()); return "closed";
        });
        assertEquals("closed", outcomes.get(1));
        assertTrue(List.of("voted", "conflict").contains(outcomes.get(0)));
        assertEquals(MeetingPollStatus.CLOSED, pollRepository.findById(poll.id()).orElseThrow().getStatus());
        assertEquals(outcomes.get(0).equals("voted") ? 1 : 0, ballots.countByPollId(poll.id()));
        login(member.getUserAccount());
        assertFalse(polls.get(meeting.getId(), poll.id()).canVote());
    }

    @Test
    void duplicateConcurrentVotesCommitExactlyOneBallot() throws Exception {
        var poll = open(false);
        assertEquals(List.of("voted", "voted"), race(() -> vote(member.getUserAccount(), poll),
            () -> vote(member.getUserAccount(), poll)));
        assertEquals(1, ballots.countByPollId(poll.id()));
    }

    @Test
    void voteAgainstCanonicalEndClosesPollAndPreservesOnlyAcceptedVote() throws Exception {
        var poll = open(false);
        var outcomes = race(() -> vote(member.getUserAccount(), poll), () -> {
            login(owner); meetings.endMeeting(meeting.getId()); return "ended";
        });
        assertEquals("ended", outcomes.get(1));
        assertTrue(List.of("voted", "conflict").contains(outcomes.get(0)));
        assertEquals(MeetingStatus.COMPLETED, meetingRepository.findById(meeting.getId()).orElseThrow().getStatus());
        var persisted = pollRepository.findById(poll.id()).orElseThrow();
        assertEquals(MeetingPollStatus.CLOSED, persisted.getStatus());
        assertNotNull(persisted.getClosedAt());
        assertEquals(outcomes.get(0).equals("voted") ? 1 : 0, ballots.countByPollId(poll.id()));
        login(owner); meetings.endMeeting(meeting.getId());
        assertEquals(persisted.getClosedAt(), pollRepository.findById(poll.id()).orElseThrow().getClosedAt());
    }

    @Test
    void terminalTransactionRollbackRestoresPollAndMeeting() {
        var poll = open(false);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            meetings.endMeeting(meeting.getId());
            throw new IllegalStateException("simulate later transaction failure");
        }));
        assertEquals(MeetingStatus.IN_PROGRESS, meetingRepository.findById(meeting.getId()).orElseThrow().getStatus());
        assertEquals(MeetingPollStatus.OPEN, pollRepository.findById(poll.id()).orElseThrow().getStatus());
        assertNull(pollRepository.findById(poll.id()).orElseThrow().getClosedAt());
    }

    @Test
    void captureFailureLeavesMeetingAndPollOpen() {
        var poll = open(false);
        doThrow(new BusinessValidationException("Capture incomplete")).when(capture).finalizeForTerminalTransition(anyLong());
        assertThrows(BusinessValidationException.class, () -> meetings.endMeeting(meeting.getId()));
        assertEquals(MeetingStatus.IN_PROGRESS, meetingRepository.findById(meeting.getId()).orElseThrow().getStatus());
        assertEquals(MeetingPollStatus.OPEN, pollRepository.findById(poll.id()).orElseThrow().getStatus());
    }

    @Test
    void cancellationClosesOpenPollAndPreservesAcceptedBallot() {
        var poll = open(false);
        vote(member.getUserAccount(), poll);
        login(owner);
        meetings.updateMeeting(meeting.getId(), new UpdateMeetingRequest(meeting.getTitle(), null,
            meeting.getStartTime(), meeting.getEndTime(), MeetingAccessType.PRIVATE, MeetingStatus.CANCELLED));
        assertEquals(MeetingPollStatus.CLOSED, pollRepository.findById(poll.id()).orElseThrow().getStatus());
        assertEquals(1, ballots.countByPollId(poll.id()));
        assertThrows(MeetingPollConflictException.class, () -> polls.create(meeting.getId(), draft(false)));
    }

    @Test
    void concurrentResultReadsUseOneDatabaseSnapshotPerResponse() throws Exception {
        var draft = polls.create(meeting.getId(), new MeetingPollRequest("Concurrent results", null,
            MeetingPollType.YES_NO_ABSTAIN, true, MeetingPollResultVisibility.LIVE, 1, 1, null,
            List.of(member.getParticipantKey())));
        var poll = polls.open(meeting.getId(), draft.id());
        race(() -> {
            login(member.getUserAccount());
            for (int index = 0; index < 20; index++) {
                polls.vote(meeting.getId(), poll.id(), new MeetingPollVoteRequest(List.of(poll.options().get(index % 2).id())));
            }
            return "written";
        }, () -> {
            login(owner);
            for (int index = 0; index < 30; index++) {
                var response = polls.get(meeting.getId(), poll.id());
                assertEquals(response.votedCount(), response.results().stream().mapToLong(MeetingPollResponse.OptionResult::count).sum());
                assertTrue(response.results().stream().allMatch(result -> result.percentage() >= 0 && result.percentage() <= 100));
            }
            return "read";
        });
        assertEquals(1, ballots.countByPollId(poll.id()));
    }

    @Test
    void actualHttpLoginCsrfVoteReconnectAndCloseReturnPersistedResults() throws Exception {
        var poll = open(true);
        HttpClient memberClient = client();
        JsonNode csrf = signIn(memberClient, member.getUserAccount());
        var missingCsrf = request(memberClient, "PUT", "/api/meetings/" + meeting.getId() + "/polls/" + poll.id() + "/my-vote",
            "{\"optionIds\":[" + poll.options().get(0).id() + "]}", null);
        assertEquals(403, missingCsrf.statusCode());
        String route = "/api/meetings/" + meeting.getId() + "/polls/" + poll.id();
        var first = request(memberClient, "PUT", route + "/my-vote",
            "{\"optionIds\":[" + poll.options().get(0).id() + "]}", csrf);
        assertEquals(200, first.statusCode(), first.body());
        JsonNode firstBody = json.readTree(first.body());
        assertFalse(firstBody.get("canSeeResults").asBoolean());
        assertTrue(firstBody.get("results").isNull());
        HttpClient reconnect = client(); signIn(reconnect, member.getUserAccount());
        var restored = request(reconnect, "GET", route, null, null);
        assertEquals(200, restored.statusCode());
        assertEquals(firstBody.get("myVote"), json.readTree(restored.body()).get("myVote"));
        HttpClient ownerClient = client(); JsonNode ownerCsrf = signIn(ownerClient, owner);
        assertEquals(200, request(ownerClient, "POST", route + "/close", null, ownerCsrf).statusCode());
        var closed = json.readTree(request(reconnect, "GET", route, null, null).body());
        assertEquals("CLOSED", closed.get("status").asText());
        assertTrue(closed.get("canSeeResults").asBoolean());
        assertFalse(closed.get("canVote").asBoolean());
        assertEquals(1, closed.get("results").get(0).get("count").asInt());
        assertEquals(100, closed.get("results").get(0).get("percentage").asInt());
        assertEquals(204, request(reconnect, "GET", "/api/meetings/" + meeting.getId() + "/polls/active", null, null).statusCode());
        assertEquals(403, request(client(), "GET", route, null, null).statusCode());
    }

    private MeetingPollResponse open(boolean change) {
        var draft = polls.create(meeting.getId(), draft(change));
        return polls.open(meeting.getId(), draft.id());
    }
    private MeetingPollRequest draft(boolean change) {
        return new MeetingPollRequest("Approve?", null, MeetingPollType.YES_NO_ABSTAIN, change,
            MeetingPollResultVisibility.AFTER_CLOSE, 1, 1, null, List.of(member.getParticipantKey()));
    }
    private String vote(UserAccountEntity account, MeetingPollResponse poll) {
        login(account);
        try {
            polls.vote(meeting.getId(), poll.id(), new MeetingPollVoteRequest(List.of(poll.options().get(0).id())));
            return "voted";
        } catch (MeetingPollConflictException conflict) { return "conflict"; }
    }
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS));
                try { return first.call(); } finally { SecurityContextHolder.clearContext(); } });
            var b = executor.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS));
                try { return second.call(); } finally { SecurityContextHolder.clearContext(); } });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private UserAccountEntity account(SystemRole role) {
        var account = new UserAccountEntity(); String key = UUID.randomUUID().toString();
        account.setPublicId(key); account.setNormalizedEmail(key + "@example.test"); account.setUsername(key);
        account.setFullName("Poll test"); account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(role);
        account.setPasswordHash(passwordEncoder.encode(PASSWORD)); account.setEmailVerified(true);
        em.persist(account); return account;
    }
    private void login(UserAccountEntity account) {
        var principal = new MeetingPrincipal(account.getId(), account.getPublicId(), account.getUsername(),
            account.getNormalizedEmail(), account.getFullName(), null, AccountStatus.ACTIVE, account.getSystemRole(),
            true, AuthProvider.LOCAL, account.getPasswordHash());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
    }
    private JsonNode signIn(HttpClient client, UserAccountEntity account) throws Exception {
        var csrf = json.readTree(request(client, "GET", "/api/auth/csrf", null, null).body());
        var response = request(client, "POST", "/api/auth/login", json.writeValueAsString(
            java.util.Map.of("usernameOrEmail", account.getUsername(), "password", PASSWORD)), csrf);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(request(client, "GET", "/api/auth/csrf", null, null).body());
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("Content-Type", "application/json");
        if (csrf != null) request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String url() { return System.getProperty("poll.postgres.url"); }
    private static String username() { return System.getProperty("poll.postgres.username", "meeting_user"); }
    private static String password() { return java.util.Objects.requireNonNull(System.getenv("POLL_TEST_DB_PASSWORD")); }
}
