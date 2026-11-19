package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.MeetingServiceApplication;
import vn.cusc.meetingservice.client.capture.CaptureClient;
import vn.cusc.meetingservice.client.conclusion.ConclusionClient;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.service.LiveKitService;

/** Two genuinely fresh Spring applications, HTTP servers, schedulers and JDBC session readers.
 * This is application-context restart proof in one JVM, not an OS process-kill experiment. */
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class Audit24RestartRuntimeTest {
    private static final String PROFILE = "audit24-restart-mocks";
    private static final String PASSWORD = "Isolated-Restart-Test-2026!";

    @Test
    void freshApplicationReadsPersistedSessionCreationReceiptAndCleanupQueue() throws Exception {
        String schema = "qna_p2_audit24_restart_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(schema.matches("qna_p2_audit24_restart_[0-9a-f]{32}"));
        ConfigurableApplicationContext first = null;
        ConfigurableApplicationContext second = null;
        boolean createdSchema = false;
        try {
            try (var connection = DriverManager.getConnection(url(), username(), databasePassword()); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                createdSchema = true;
            }
            first = application(schema, true);
            ObjectMapper json = first.getBean(ObjectMapper.class);
            var transaction = new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
            var accountRepository = first.getBean(UserAccountRepository.class);
            var passwordEncoder = first.getBean(PasswordEncoder.class);
            String publicId = transaction.execute(status -> {
                var account = new UserAccountEntity();
                account.setPublicId(UUID.randomUUID().toString());
                account.setUsername("restart-" + UUID.randomUUID());
                account.setNormalizedEmail("restart-" + UUID.randomUUID() + "@example.test");
                account.setFullName("Restart test owner");
                account.setPasswordHash(passwordEncoder.encode(PASSWORD));
                account.setAccountStatus(AccountStatus.ACTIVE);
                account.setSystemRole(SystemRole.USER);
                account.setEmailVerified(true);
                return accountRepository.saveAndFlush(account).getPublicId();
            });
            var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            var browser = HttpClient.newBuilder().cookieHandler(cookieManager).connectTimeout(Duration.ofSeconds(10)).build();
            String username = first.getBean(UserAccountRepository.class).findByPublicId(publicId).orElseThrow().getUsername();
            int firstPort = port(first);
            var login = request(browser, json, firstPort, "POST", "/api/auth/login", json.createObjectNode()
                .put("usernameOrEmail", username).put("password", PASSWORD), true);
            assertEquals(200, login.statusCode());
            String sessionCookie = cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
            var creation = json.createObjectNode().put("title", "Application restart proof")
                .put("description", "Persisted operation and session only")
                .put("accessType", "PUBLIC").put("creationMode", "LATER")
                .put("clientRequestId", UUID.randomUUID().toString());
            creation.putArray("participants");
            var created = request(browser, json, firstPort, "POST", "/api/meetings", creation, true);
            assertEquals(201, created.statusCode());
            JsonNode receipt = json.readTree(created.body());
            long meetingId = receipt.path("id").asLong();
            String roomName = receipt.path("roomName").asText();
            LiveKitService firstLiveKit = first.getBean(LiveKitService.class);
            verify(firstLiveKit, times(1)).createRoom(roomName);
            var ended = request(browser, json, firstPort, "POST", "/api/meetings/" + meetingId + "/end", null, true);
            assertEquals(202, ended.statusCode());
            assertEquals("true", ended.headers().firstValue("X-Meeting-Cleanup-Pending").orElseThrow());
            JdbcTemplate initialJdbc = first.getBean(JdbcTemplate.class);
            assertEquals(1L, initialJdbc.queryForObject("SELECT count(*) FROM meeting_media_cleanup_jobs WHERE room_name = ?", Long.class, roomName));
            assertTrue(initialJdbc.queryForObject("SELECT count(*) FROM spring_session", Long.class) > 0);
            verifyNoInteractions(first.getBean(CaptureClient.class), first.getBean(SttClient.class), first.getBean(ConclusionClient.class));

            first.close();
            assertFalse(first.isActive());
            first = null;
            second = application(schema, false);
            assertNotSame(firstLiveKit, second.getBean(LiveKitService.class));
            int secondPort = port(second);
            var me = request(browser, json, secondPort, "GET", "/api/auth/me", null, false);
            assertEquals(200, me.statusCode());
            assertTrue(json.readTree(me.body()).path("authenticated").asBoolean());
            assertEquals(publicId, json.readTree(me.body()).path("user").path("publicId").asText());
            assertEquals(sessionCookie, cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue());

            var replay = request(browser, json, secondPort, "POST", "/api/meetings", creation, true);
            assertEquals(201, replay.statusCode());
            assertEquals(receipt, json.readTree(replay.body()));
            verify(second.getBean(LiveKitService.class), never()).createRoom(anyString());
            JdbcTemplate freshJdbc = second.getBean(JdbcTemplate.class);
            assertEquals(1L, freshJdbc.queryForObject("SELECT count(*) FROM meetings", Long.class));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (freshJdbc.queryForObject("SELECT count(*) FROM meeting_media_cleanup_jobs WHERE room_name = ?", Long.class, roomName) > 0
                && System.nanoTime() < deadline) Thread.sleep(100);
            assertEquals(0L, freshJdbc.queryForObject("SELECT count(*) FROM meeting_media_cleanup_jobs WHERE room_name = ?", Long.class, roomName));
            verify(second.getBean(LiveKitService.class), atLeastOnce()).deleteRoom(roomName);
            verifyNoInteractions(second.getBean(CaptureClient.class), second.getBean(SttClient.class), second.getBean(ConclusionClient.class));
        } finally {
            if (first != null) first.close();
            if (second != null) second.close();
            if (createdSchema) try (var connection = DriverManager.getConnection(url(), username(), databasePassword()); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static int port(ConfigurableApplicationContext context) { return ((ServletWebServerApplicationContext) context).getWebServer().getPort(); }
    private static ConfigurableApplicationContext application(String schema, boolean rejectCleanup) {
        String scopedUrl = url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new SpringApplicationBuilder(MeetingServiceApplication.class, ExternalMocks.class).run(
            "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=test," + PROFILE,
            "--spring.datasource.url=" + scopedUrl, "--spring.datasource.username=" + username(),
            "--spring.datasource.password=" + databasePassword(), "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.flyway.enabled=true", "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--spring.session.jdbc.initialize-schema=never", "--livekit.cleanup.interval-ms=100",
            "--test.restart.reject-cleanup=" + rejectCleanup, "--storage.cleanup-enabled=false",
            "--meeting.terminal-recovery-enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
    private static HttpResponse<String> request(HttpClient browser, ObjectMapper json, int port, String method,
        String path, JsonNode body, boolean mutation) throws Exception {
        String csrf = null;
        if (mutation) {
            var response = request(browser, json, port, "GET", "/api/auth/csrf", null, false);
            assertEquals(200, response.statusCode());
            csrf = json.readTree(response.body()).path("token").asText();
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return browser.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String url() { return System.getProperty("qna.postgres.url"); }
    private static String username() { return System.getProperty("qna.postgres.username", "qna_test"); }
    private static String databasePassword() { return System.getenv().getOrDefault("QNA_TEST_DB_PASSWORD", ""); }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile(PROFILE)
    @EnableScheduling
    static class ExternalMocks {
        @Bean @Primary LiveKitService restartLiveKit(Environment environment) {
            var fake = mock(LiveKitService.class);
            when(fake.createRoom(anyString())).thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_restart", 0, 0));
            when(fake.ensureRoomExists(anyString())).thenAnswer(call -> new LiveKitRoomResponse(call.getArgument(0), "RM_restart", 0, 0));
            when(fake.generateJoinToken(anyString(), anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean())).thenReturn("restart-test-only-token");
            if (environment.getProperty("test.restart.reject-cleanup", Boolean.class, true))
                doThrow(new LiveKitOperationException("Isolated test cleanup temporarily unavailable")).when(fake).deleteRoom(anyString());
            return fake;
        }
        @Bean @Primary CaptureClient restartCapture() { return mock(CaptureClient.class); }
        @Bean @Primary SttClient restartStt() { return mock(SttClient.class); }
        @Bean @Primary ConclusionClient restartConclusion() { return mock(ConclusionClient.class); }
    }
}
