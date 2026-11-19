package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.service.impl.LiveKitServiceImpl;

/** Uses the actual SDK against an owned loopback HTTP fixture, never a real LiveKit room. */
class LiveKitTransportSafetyTest {
    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 502, 503})
    void unsuccessfulRemovalIsNeverReportedSuccessful(int status) throws Exception {
        withServer(status, "{\"code\":\"unavailable\",\"msg\":\"temporary\"}", service -> {
            assertThrows(LiveKitOperationException.class, () -> service.deleteRoom("meeting-test"));
            assertThrows(LiveKitOperationException.class, () -> service.removeParticipant("meeting-test", "guest:test"));
        });
    }
    @Test void providerNotFoundIsIdempotentButProxy404IsNot() throws Exception {
        withServer(404, "{\"code\":\"not_found\",\"msg\":\"room absent\"}", service -> {
            assertDoesNotThrow(() -> service.deleteRoom("meeting-test"));
            assertDoesNotThrow(() -> service.removeParticipant("meeting-test", "guest:test"));
            assertDoesNotThrow(() -> service.removeParticipantsByAttendeeId("meeting-test", "guest:test"));
        });
        withServer(404, "<html>proxy unavailable</html>", service ->
            assertThrows(LiveKitOperationException.class, () -> service.deleteRoom("meeting-test")));
    }
    @Test void successfulRemovalIsAcknowledgedByActualSdk() throws Exception {
        withServer(200, "{}", service -> {
            assertDoesNotThrow(() -> service.deleteRoom("meeting-test"));
            assertDoesNotThrow(() -> service.removeParticipant("meeting-test", "guest:test"));
        });
    }
    @Test void initialJoinCredentialsExpireAfterFiveMinutesWithMeetingScopedGrants() throws Exception {
        var service = new LiveKitServiceImpl(properties("http://127.0.0.1:1"));
        String jwt = service.generateJoinToken("meeting-test", "guest:test:connection", "Guest", Map.of("connectionId", "connection"), true, true);
        var payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        long remaining = payload.path("exp").asLong() - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        assertTrue(remaining >= 298 && remaining <= 300, "bounded initial credential lifetime");
        assertEquals("meeting-test", payload.path("video").path("room").asText());
        assertTrue(payload.path("video").path("roomJoin").asBoolean());
        assertEquals("guest:test:connection", payload.path("sub").asText());
    }
    @Test void hungProviderHasBoundedWholeCallTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try { Thread.sleep(4000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(503, -1); } catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var props = properties("http://127.0.0.1:" + server.getAddress().getPort()); props.setRequestTimeoutSeconds(1);
            var service = new LiveKitServiceImpl(props); long start = System.nanoTime();
            assertThrows(LiveKitOperationException.class, () -> service.deleteRoom("meeting-test"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
        } finally { server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private void withServer(int status, String body, ThrowingConsumer check) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try { check.accept(new LiveKitServiceImpl(properties("http://127.0.0.1:" + server.getAddress().getPort()))); }
        finally { server.stop(0); }
    }
    private LiveKitProperties properties(String url) {
        var properties = new LiveKitProperties(); properties.setUrl(url); properties.setApiKey("isolated-sdk-test-key");
        properties.setApiSecret("isolated-sdk-test-secret-for-jwt-verification"); return properties;
    }
    private interface ThrowingConsumer { void accept(LiveKitService service) throws Exception; }
}
