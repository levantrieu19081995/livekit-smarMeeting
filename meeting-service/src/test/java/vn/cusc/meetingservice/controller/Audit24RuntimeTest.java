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
class Audit24RuntimeTest {
    private static final String SCHEMA = "qna_p2_audit24_http_" + UUID.randomUUID().toString().replace("-", "");
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
    @org.springframework.boot.test.mock.mockito.SpyBean private vn.cusc.meetingservice.service.MeetingMediaCleanupService media;
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
        properties.add("spring.datasource.username", Audit24RuntimeTest::username);
        properties.add("spring.datasource.password", Audit24RuntimeTest::password);
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
        tx.executeWithoutResult(status -> { em.createNativeQuery("DELETE FROM meetings").executeUpdate(); em.createNativeQuery("DELETE FROM meeting_media_cleanup_jobs").executeUpdate(); });
    }

    @AfterAll static void dropOnlyOwnedSchema() throws Exception {
        if (!schemaCreated) return;
        assertTrue(SCHEMA.matches("qna_p2_audit24_http_[0-9a-f]{32}"));
        try (var connection = DriverManager.getConnection(url(), username(), password()); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private vn.cusc.meetingservice.service.MeetingMediaCleanupWorker cleanupWorker;
    @Autowired private vn.cusc.meetingservice.repository.MeetingMediaCleanupJobRepository cleanupJobs;
    @Autowired private vn.cusc.meetingservice.repository.UserAccountRepository accounts;
    @Autowired private vn.cusc.meetingservice.repository.MeetingRepository meetings;
    @Autowired private vn.cusc.meetingservice.service.MeetingTerminalRecoveryService terminalRecovery;
    @Autowired private vn.cusc.meetingservice.service.MeetingFileCleanupService fileCleanup;
    @Autowired private vn.cusc.meetingservice.repository.MeetingFileCleanupJobRepository fileJobs;
    @Autowired private vn.cusc.meetingservice.service.FileStorageService storage;
    @Autowired private vn.cusc.meetingservice.service.MeetingCaptureStateService captureState;

    @Test void lateCaptureStartAcknowledgementWaitsForRecoveryAndCannotReviveCompletedSession() throws Exception {
        Long sessionId = tx.execute(status -> {
            var session = new MeetingCaptureSessionEntity(); session.setMeeting(em.find(MeetingEntity.class, meetingId));
            session.setStatus(CaptureStatus.STARTING); em.persist(session); em.flush(); return session.getId();
        });
        var holding = new CountDownLatch(1); var release = new CountDownLatch(1); var acknowledgementStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var recovery = executor.submit(() -> tx.execute(status -> {
                captureState.markRecoveryCompleted(new vn.cusc.meetingservice.service.MeetingCaptureStateService.RecoveryClaim(sessionId, meetingId, CaptureStatus.STARTING, null),
                    new vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse(sessionId, false, "remote-capture", "COMPLETED", "stopped", "owned-manifest.json", "now"));
                holding.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                return null;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            var acknowledgement = executor.submit(() -> {
                acknowledgementStarted.countDown();
                return captureState.markStartSucceeded(sessionId, new vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse("remote-capture", "RUNNING", "late reply", null, null));
            });
            assertTrue(acknowledgementStarted.await(5, TimeUnit.SECONDS));
            boolean waitingForLock = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !acknowledgement.isDone()) {
                try (var db = DriverManager.getConnection(url(), username(), password()); var statement = db.createStatement();
                     var result = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%meetings%'")) {
                    result.next(); if (result.getInt(1) > 0) { waitingForLock = true; break; }
                }
                Thread.sleep(10);
            }
            assertTrue(waitingForLock, "late acknowledgement must wait on the same meeting lock");
            release.countDown(); recovery.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> acknowledgement.get(5, TimeUnit.SECONDS));
            assertInstanceOf(vn.cusc.meetingservice.exception.BusinessValidationException.class, failure.getCause());
            assertEquals(CaptureStatus.COMPLETED, tx.execute(status -> em.find(MeetingCaptureSessionEntity.class, sessionId).getStatus()));
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void actualUploadAndMeetingDeletionPersistOneNativeFileCleanupIntent() throws Exception {
        String boundary = "Audit24" + UUID.randomUUID().toString().replace("-", "");
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audit24.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\nOwned isolated verification file\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + base(meetingId) + "/documents"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header(ownerBrowser.csrf().path("headerName").asText(), ownerBrowser.csrf().path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(multipart)).build();
        var uploaded = body(ownerBrowser.client().send(request, HttpResponse.BodyHandlers.ofString()), 201);
        String path = tx.execute(status -> em.find(MeetingDocumentEntity.class, uploaded.path("id").asLong()).getStoragePath());
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path)));
        body(send(ownerBrowser, "DELETE", base(meetingId), null, null, true), 204);
        fileCleanup.enqueue(path); fileCleanup.enqueue(path);
        var job = fileJobs.findByStoragePath(java.nio.file.Path.of(path).toAbsolutePath().normalize().toString()).orElseThrow();
        var claim = fileCleanup.claim(job.getId()).orElseThrow();
        storage.delete(java.nio.file.Path.of(claim.path())); fileCleanup.completed(claim);
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(path))); assertFalse(fileJobs.existsById(job.getId()));
    }

    @Test void scheduledDeadlineReconcilesRecordingAndMediaAndRetainsReadOnlyHistory() throws Exception {
        ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Scheduled speaker");
        tx.executeWithoutResult(status -> em.find(MeetingEntity.class, meetingId).setEndTime(LocalDateTime.now().minusMinutes(1)));
        terminalRecovery.recoverExpiredMeeting(meetingId);
        assertEquals(MeetingStatus.COMPLETED, meetings.findById(meetingId).orElseThrow().getStatus());
        verify(capture).finalizeForTerminalTransition(meetingId); verify(liveKit).deleteRoom(anyString());
        assertEquals("CANCELLED", list(ownerBrowser, null).path("content").get(0).path("status").asText());
    }

    @Test void slowRoomProvisioningDoesNotBlockEndAndCannotIssueTokenAfterEnd() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(liveKit.ensureRoomExists(anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return new LiveKitRoomResponse(call.getArgument(0), "RM_slow", 0, 0);
        });
        var executor = Executors.newFixedThreadPool(2);
        long before = meetingCount("meeting_participant_connections", meetingId);
        try {
            var joining = executor.submit(() -> send(guestBrowser, "POST", base(meetingId) + "/join",
                json.writeValueAsString(Map.of("participantType", "GUEST", "displayName", "Late guest", "pointId", pointA)), null, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var ending = executor.submit(() -> send(ownerBrowser, "POST", base(meetingId) + "/end", null, null, true));
            body(ending.get(3, TimeUnit.SECONDS), 204);
            release.countDown(); body(joining.get(5, TimeUnit.SECONDS), 400);
            assertEquals(before, meetingCount("meeting_participant_connections", meetingId));
            verify(liveKit, never()).generateJoinToken(anyString(), anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void terminalJoinCleanupIsSerializedWithExplicitAdminReopen() throws Exception {
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setSystemRole(SystemRole.ADMIN));
        var provisionEntered = new CountDownLatch(1); var provisionRelease = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1); var cleanupRelease = new CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(liveKit.ensureRoomExists(anyString())).thenAnswer(call -> {
            provisionEntered.countDown(); assertTrue(provisionRelease.await(10, TimeUnit.SECONDS));
            return new LiveKitRoomResponse(call.getArgument(0), "RM_delayed", 0, 0);
        });
        doAnswer(call -> {
            if (armed.get()) { cleanupEntered.countDown(); assertTrue(cleanupRelease.await(10, TimeUnit.SECONDS)); }
            return call.callRealMethod();
        }).when(media).enqueue(eq(meetingId), eq("DELETE_ROOM"), anyString(), isNull());
        var executor = Executors.newFixedThreadPool(2);
        String route = "/api/admin/meeting-tools/meetings/" + meetingId + "/override";
        try {
            var joining = executor.submit(() -> send(guestBrowser, "POST", base(meetingId) + "/join", json.writeValueAsString(
                Map.of("participantType", "GUEST", "displayName", "Delayed join", "pointId", pointA)), null, true));
            assertTrue(provisionEntered.await(5, TimeUnit.SECONDS));
            body(send(ownerBrowser, "PATCH", route, json.writeValueAsString(Map.of("status", "CANCELLED", "reason", "Cancel")), null, true), 204);
            armed.set(true); provisionRelease.countDown(); assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            var reopening = executor.submit(() -> send(ownerBrowser, "PATCH", route,
                json.writeValueAsString(Map.of("status", "IN_PROGRESS", "reason", "Reopen")), null, true));
            boolean waitingForLock = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !reopening.isDone()) {
                try (var db = DriverManager.getConnection(url(), username(), password()); var statement = db.createStatement();
                     var result = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%meetings%'")) {
                    result.next(); if (result.getInt(1) > 0) { waitingForLock = true; break; }
                }
                Thread.sleep(10);
            }
            assertTrue(waitingForLock, "admin reopen must wait until the rejected join commits its cleanup decision");
            armed.set(false); cleanupRelease.countDown();
            body(joining.get(5, TimeUnit.SECONDS), 400);
            var reopened = reopening.get(5, TimeUnit.SECONDS);
            if (reopened.statusCode() == 400) {
                assertTrue(reopened.body().contains("được đóng"), reopened.body());
                assertEquals(MeetingStatus.CANCELLED, tx.execute(status -> em.find(MeetingEntity.class, meetingId).getStatus()));
                body(send(ownerBrowser, "PATCH", route, json.writeValueAsString(Map.of("status", "IN_PROGRESS", "reason", "Reopen after cleanup")), null, true), 204);
            } else body(reopened, 204);
            assertEquals(MeetingStatus.IN_PROGRESS, tx.execute(status -> em.find(MeetingEntity.class, meetingId).getStatus()));
            assertFalse(cleanupJobs.existsByMeetingId(meetingId));
            verify(liveKit, times(2)).deleteRoom(anyString());
        } finally {
            armed.set(false); provisionRelease.countDown(); cleanupRelease.countDown();
            executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void failedRoomCleanupIsDurableAndReconstructedWorkerRetriesAfterTerminalCommit() throws Exception {
        doThrow(new LiveKitOperationException("simulated network outage")).when(liveKit).deleteRoom(anyString());
        var response = send(ownerBrowser, "POST", base(meetingId) + "/end", null, null, true);
        body(response, 202); assertEquals("true", response.headers().firstValue("X-Meeting-Cleanup-Pending").orElse(null));
        assertEquals("COMPLETED", body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200).path("status").asText());
        Long id = tx.execute(status -> cleanupJobs.findAll().stream().filter(j -> Objects.equals(j.getMeetingId(), meetingId)).findFirst().orElseThrow().getId());
        assertEquals(1, tx.execute(status -> cleanupJobs.findById(id).orElseThrow().getAttempts()).intValue());
        doNothing().when(liveKit).deleteRoom(anyString());
        tx.executeWithoutResult(status -> cleanupJobs.findById(id).orElseThrow().setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)));
        var restartedWorker = new vn.cusc.meetingservice.service.MeetingMediaCleanupWorker(cleanupJobs, liveKit, transactions, accounts, meetings);
        restartedWorker.process(id);
        assertFalse(cleanupJobs.existsById(id)); verify(liveKit, times(2)).deleteRoom(anyString());
    }

    @Test void deletionFinalizesCaptureAndPersistsCleanupWithoutMeetingForeignKey() throws Exception {
        doThrow(new LiveKitOperationException("network down")).when(liveKit).deleteRoom(anyString());
        body(send(ownerBrowser, "DELETE", base(meetingId), null, null, true), 202);
        assertFalse(meetings.existsById(meetingId));
        verify(capture).finalizeForTerminalTransition(meetingId);
        assertTrue(cleanupJobs.existsByMeetingId(meetingId));
        doNothing().when(liveKit).deleteRoom(anyString());
        tx.executeWithoutResult(status -> cleanupJobs.findAll().forEach(j -> j.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1))));
        cleanupWorker.recoverOne(); assertFalse(cleanupJobs.existsByMeetingId(meetingId));
    }

    @Test void cancellationFinalizesRecordingAndSpeakingBeforeTerminalState() throws Exception {
        ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting");
        var input = updatePayload("Cancelled"); input.put("status", "CANCELLED");
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 200);
        verify(capture).finalizeForTerminalTransition(meetingId); verify(liveKit).deleteRoom(anyString());
        assertEquals("CANCELLED", list(ownerBrowser, null).path("content").get(0).path("status").asText());
        body(send(guestBrowser, "POST", base(meetingId) + "/join", guestResumePayload(guestId, guestToken, pointA), null, true), 400);
    }

    @Test void adminCancellationClosesPollSpeakingCaptureAndFencesReopenUntilCleanup() throws Exception {
        tx.executeWithoutResult(status -> em.find(UserAccountEntity.class, owner.getId()).setSystemRole(SystemRole.ADMIN));
        ask(memberBrowser, memberToken, UUID.randomUUID().toString(), "Waiting speaker");
        var poll = body(send(guestBrowser, "POST", base(meetingId) + "/polls", json.writeValueAsString(Map.of(
            "question", "Admin cancel test", "type", "YES_NO_ABSTAIN", "resultVisibility", "LIVE",
            "minSelections", 1, "maxSelections", 1, "eligibleParticipantKeys", List.of())), guestToken, true), 201);
        long pollId = poll.path("id").asLong();
        body(send(guestBrowser, "POST", base(meetingId) + "/polls/" + pollId + "/open", null, guestToken, true), 200);
        doThrow(new LiveKitOperationException("network down")).when(liveKit).deleteRoom(anyString());
        String route = "/api/admin/meeting-tools/meetings/" + meetingId + "/override";
        var cancelled = send(ownerBrowser, "PATCH", route, json.writeValueAsString(Map.of("status", "CANCELLED", "reason", "Cancel meeting")), null, true);
        body(cancelled, 202);
        assertEquals("true", cancelled.headers().firstValue("X-Meeting-Cleanup-Pending").orElseThrow());
        verify(capture).finalizeForTerminalTransition(meetingId);
        assertEquals("CANCELLED", list(ownerBrowser, null).path("content").get(0).path("status").asText());
        assertEquals(MeetingPollStatus.CLOSED, tx.execute(status -> em.find(MeetingPollEntity.class, pollId).getStatus()));
        String reopen = json.writeValueAsString(Map.of("status", "IN_PROGRESS", "reason", "Reopen after cleanup"));
        body(send(ownerBrowser, "PATCH", route, reopen, null, true), 400);
        Long job = tx.execute(status -> cleanupJobs.findAll().stream().filter(j -> Objects.equals(j.getMeetingId(), meetingId)).findFirst().orElseThrow().getId());
        doNothing().when(liveKit).deleteRoom(anyString());
        tx.executeWithoutResult(status -> cleanupJobs.findById(job).orElseThrow().setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)));
        cleanupWorker.process(job);
        body(send(ownerBrowser, "PATCH", route, reopen, null, true), 204);
        assertEquals(MeetingStatus.IN_PROGRESS, tx.execute(status -> em.find(MeetingEntity.class, meetingId).getStatus()));
    }

    @ParameterizedTest @ValueSource(strings = {"INSTANT", "LATER", "SCHEDULED_PUBLIC", "SCHEDULED_PRIVATE"})
    void everyCreationModeHasDurableConcurrentRetryAndDeletedReceipt(String mode) throws Exception {
        String payload = quickPayload(mode, UUID.randomUUID().toString());
        long before = count("meetings");
        var replies = race(() -> send(ownerBrowser, "POST", "/api/meetings", payload, null, true),
            () -> send(ownerBrowser, "POST", "/api/meetings", payload, null, true));
        var first = body(replies.get(0), 201); var second = body(replies.get(1), 201);
        long id = first.path("id").asLong(); assertEquals(first.path("id"), second.path("id"));
        assertEquals(before + 1, count("meetings")); verify(liveKit, times(1)).createRoom(anyString());
        assertEquals(first.path("id"), body(send(signedIn(owner), "POST", "/api/meetings", payload, null, true), 201).path("id"));
        body(send(ownerBrowser, "DELETE", base(id), null, null, true), 204);
        body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 410);
        verify(liveKit, times(1)).createRoom(anyString());
    }

    @Test void orphanCleanupCannotDeleteRoomCommittedBySuccessfulCreationRetry() throws Exception {
        when(liveKit.createRoom(anyString())).thenThrow(new LiveKitOperationException("lost room response"))
            .thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_retry", 0, 0));
        String payload = quickPayload("INSTANT", UUID.randomUUID().toString());
        body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 502);
        Long orphan = tx.execute(status -> cleanupJobs.findAll().stream().filter(j -> j.getOperation().equals("DELETE_ORPHAN_ROOM")).findFirst().orElseThrow().getId());
        body(send(ownerBrowser, "POST", "/api/meetings", payload, null, true), 201);
        cleanupWorker.process(orphan);
        verify(liveKit, never()).deleteRoom(anyString()); assertFalse(cleanupJobs.existsById(orphan));
    }

    @Test void atomicRosterRejectsStaleBaselineInsteadOfOverwritingConcurrentRoleChange() throws Exception {
        var chair = participantEntry(chairman); var sec = participantEntry(secretary); var attendee = participantEntry(member);
        var baseline = List.of(rosterBaseline(chair), rosterBaseline(sec), rosterBaseline(attendee));
        String key = chair.path("participantKey").asText();
        body(send(ownerBrowser, "PUT", base(meetingId) + "/participants/" + key + "/role", "{\"role\":\"MEMBER\"}", null, true), 200);
        var input = updatePayload("Must not overwrite"); input.put("participants", List.of(rosterItem(chair, "CHAIRMAN"), rosterItem(sec, "SECRETARY")));
        input.put("rosterBaseline", baseline);
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 409);
        assertEquals("MEMBER", participantEntry(chairman).path("role").asText());
        assertNotEquals("Must not overwrite", body(send(ownerBrowser, "GET", base(meetingId), null, null, false), 200).path("title").asText());
    }

    @Test void atomicRosterPreservesUnlistedParticipantWhoJoinedAfterEditorLoaded() throws Exception {
        var chair = participantEntry(chairman); var sec = participantEntry(secretary); var attendee = participantEntry(member);
        var baseline = List.of(rosterBaseline(chair), rosterBaseline(sec), rosterBaseline(attendee));
        var newJoin = join(signedIn(outsider), pointA, "AUTHENTICATED", "New attendee");
        var input = updatePayload("New attendee preserved"); input.put("participants", List.of(rosterItem(chair, "CHAIRMAN"), rosterItem(sec, "SECRETARY"), rosterItem(attendee, "MEMBER")));
        input.put("rosterBaseline", baseline);
        body(send(ownerBrowser, "PUT", base(meetingId), json.writeValueAsString(input), null, true), 200);
        assertNotEquals("REMOVED", participantEntry(outsider).path("status").asText());
        body(send(signedIn(outsider), "GET", base(meetingId) + "/chat/messages", null, newJoin.path("participant").path("chatWriteToken").asText(), false), 200);
    }

    @Test void chatLostResponseRetryAndCursorReloadUseAuthoritativeStoredMessages() throws Exception {
        String id = UUID.randomUUID().toString(); String payload = json.writeValueAsString(Map.of("clientMessageId", id, "content", "  Persisted first  "));
        var replies = race(() -> send(memberBrowser, "POST", base(meetingId) + "/chat/messages", payload, memberToken, true),
            () -> send(memberBrowser, "POST", base(meetingId) + "/chat/messages", payload, memberToken, true));
        var one = body(replies.get(0), 201); var two = body(replies.get(1), 201); assertEquals(one.path("id"), two.path("id"));
        assertEquals(member.getFullName(), one.path("senderDisplayName").asText()); assertEquals("Persisted first", one.path("content").asText());
        var history = body(send(memberBrowser, "GET", base(meetingId) + "/chat/messages?afterId=0&size=100", null, memberToken, false), 200);
        assertEquals(1, history.path("content").size()); assertEquals(one.path("id"), history.path("nextAfterId"));
        var incremental = body(send(memberBrowser, "GET", base(meetingId) + "/chat/messages?afterId=" + one.path("id").asLong(), null, memberToken, false), 200);
        assertEquals(0, incremental.path("content").size());
        body(send(guestBrowser, "POST", base(meetingId) + "/chat/messages", payload, guestToken, true), 403);
        body(send(memberBrowser, "POST", base(meetingId) + "/chat/messages", json.writeValueAsString(Map.of("clientMessageId", id, "content", "Different")), memberToken, true), 400);
    }

    @Test void draftRevisionReturnedAfterFlushRejectsStaleConcurrentEditor() throws Exception {
        tx.executeWithoutResult(status -> {
            var value = new MeetingMinutesEntity(); value.setMeeting(em.find(MeetingEntity.class, meetingId)); value.setVersion(1);
            value.setSource(MinutesVersionSource.CHAIRMAN_EDITED); value.setStatus(MinutesStatus.DRAFT);
            value.setMinutesJson("{\"title\":\"Original\"}"); value.setGeneratedAt(LocalDateTime.now()); value.setCreatedByAccount(em.find(UserAccountEntity.class, owner.getId())); em.persist(value);
        });
        String path = base(meetingId) + "/minutes/versions/1";
        var original = body(send(ownerBrowser, "GET", path, null, null, false), 200);
        long revision = original.path("revision").asLong();
        String first = json.writeValueAsString(Map.of("expectedRevision", revision, "content", Map.of("title", "Editor A")));
        String second = json.writeValueAsString(Map.of("expectedRevision", revision, "content", Map.of("title", "Editor B")));
        var replies = race(() -> send(ownerBrowser, "PUT", path, first, null, true), () -> send(ownerBrowser, "PUT", path, second, null, true));
        assertEquals(List.of(200, 400), replies.stream().map(HttpResponse::statusCode).sorted().toList());
        var saved = body(send(ownerBrowser, "GET", path, null, null, false), 200); assertEquals(revision + 1, saved.path("revision").asLong());
        var success = json.readTree(replies.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow().body());
        assertEquals(saved.path("revision"), success.path("revision"));
        body(send(ownerBrowser, "PUT", path, "{\"content\":{\"title\":\"Missing revision\"}}", null, true), 400);
    }

    @Test void hundredLogicalGuestsBehindOneAddressHaveDistinctActorsAndPersistentBoundedChatPage() throws Exception {
        var executor = Executors.newFixedThreadPool(10);
        try {
            var joins = new ArrayList<Future<JsonNode>>();
            for (int index = 0; index < 100; index++) {
                final int number = index;
                joins.add(executor.submit(() -> join(guestBrowser, pointA, "GUEST", "Logical guest " + number)));
            }
            var actors = new HashSet<String>(); var tokens = new ArrayList<String>();
            for (var task : joins) {
                var result = task.get(30, TimeUnit.SECONDS); actors.add(result.path("participant").path("attendeeId").asText());
                tokens.add(result.path("participant").path("chatWriteToken").asText());
            }
            assertEquals(100, actors.size()); assertEquals(100, new HashSet<>(tokens).size());
            var writes = new ArrayList<Future<JsonNode>>();
            for (int index = 0; index < tokens.size(); index++) {
                final int number = index;
                writes.add(executor.submit(() -> body(send(guestBrowser, "POST", base(meetingId) + "/chat/messages",
                    json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID().toString(), "content", "Stored guest message " + number)), tokens.get(number), true), 201)));
            }
            for (var task : writes) task.get(30, TimeUnit.SECONDS);
            var history = body(send(guestBrowser, "GET", base(meetingId) + "/chat/messages?size=100", null, tokens.get(0), false), 200);
            assertEquals(100, history.path("content").size());
            long previous = 0;
            for (var row : history.path("content")) { assertTrue(row.path("id").asLong() > previous); previous = row.path("id").asLong(); }
            assertEquals(previous, history.path("nextAfterId").asLong());
            body(send(guestBrowser, "GET", base(meetingId) + "/chat/messages?size=101", null, tokens.get(0), false), 400);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private Map<String, Object> rosterBaseline(JsonNode row) { return Map.of("participantKey", row.path("participantKey").asText(), "role", row.path("role").asText()); }
    private String quickPayload(String mode, String key) throws Exception {
        var input = new LinkedHashMap<String, Object>(); input.put("title", "Retry " + key); input.put("description", "No business changes"); input.put("clientRequestId", key);
        input.put("creationMode", mode.startsWith("SCHEDULED") ? "SCHEDULED" : mode); input.put("accessType", mode.endsWith("PRIVATE") ? "PRIVATE" : "PUBLIC");
        input.put("participants", mode.endsWith("PRIVATE") ? List.of(Map.of("userPublicId", chairman.getPublicId(), "role", "CHAIRMAN"), Map.of("userPublicId", secretary.getPublicId(), "role", "SECRETARY")) : List.of());
        if (mode.startsWith("SCHEDULED")) { input.put("startTime", LocalDateTime.now().minusMinutes(5).toString()); input.put("endTime", LocalDateTime.now().plusHours(2).toString()); }
        return json.writeValueAsString(input);
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

