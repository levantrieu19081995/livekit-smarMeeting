package vn.cusc.meetingservice.client.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.stt.request.StartSttJobRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobStatusResponse;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;

class SttClientImplTest {

    private MockRestServiceServer server;
    private SttClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();

        AiServiceProperties properties = new AiServiceProperties();
        properties.setInternalApiKey("test-key");
        client = new SttClientImpl(builder.build(), properties);
    }

    @Test
    void startJobSendsRequestIdContract() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andExpect(content().json("""
                {
                  "requestId": "minutes-job-15",
                  "meetingId": 12,
                  "manifestPaths": ["manifest-A", "manifest-B"]
                }
                """))
            .andRespond(withSuccess("""
                {
                  "processingId": "python-123",
                  "meetingId": 12,
                  "status": "QUEUED"
                }
                """, MediaType.APPLICATION_JSON));

        client.startJob(new StartSttJobRequest("minutes-job-15", 12L, java.util.List.of("manifest-A", "manifest-B")));

        server.verify();
    }

    @Test
    void getResultUsesExpectedContract() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andRespond(withSuccess(completedJson("text"), MediaType.APPLICATION_JSON));

        SttJobResultResponse response = client.getResult("python-123");

        assertEquals("python-123", response.processingId());
        assertEquals(12L, response.result().meetingId());
        server.verify();
    }

    @Test
    void getResultMapsCompleteDto() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andRespond(withSuccess(completedJson("Xin chao"), MediaType.APPLICATION_JSON));

        SttJobResultResponse response = client.getResult("python-123");

        assertEquals("python-123", response.processingId());
        assertEquals(12L, response.result().meetingId());
        assertEquals("session-a", response.result().sessionIds().get(0));
        assertEquals(SttTerminalStatus.COMPLETED, response.result().status());
        assertEquals(2, response.result().totalManifests());
        assertEquals(3, response.result().totalSegments());
        assertEquals(3, response.result().totalChunks());
        assertEquals(3, response.result().processedChunks());
        assertEquals(3, response.result().succeededChunks());
        assertEquals(0, response.result().failedChunks());
        var transcript = response.result().transcripts().get(0);
        assertEquals("session-a", transcript.sessionId());
        assertEquals("attendee-1", transcript.attendeeId());
        assertEquals("Nguyen Van A", transcript.speakerName());
        assertEquals("segment-1", transcript.segmentId());
        assertEquals(0, transcript.segmentIndex());
        assertEquals(0, transcript.chunkIndex());
        assertEquals(0L, transcript.chunkStartMs());
        assertEquals(12345L, transcript.chunkDurationMs());
        assertEquals("2026-08-26T09:00:00Z", transcript.segmentStartedAt());
        assertEquals("2026-08-26T09:00:01Z", transcript.absoluteStartTime());
        assertEquals("meeting-12/session-a/chunk.wav", transcript.filePath());
        assertEquals("Xin chao", transcript.text());
    }

    @Test
    void getResultAllowsNullableTranscriptValues() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andRespond(withSuccess(nullableJson(), MediaType.APPLICATION_JSON));

        SttJobResultResponse response = client.getResult("python-123");

        var transcript = response.result().transcripts().get(0);
        assertNull(transcript.attendeeId());
        assertNull(transcript.speakerName());
        assertNull(transcript.segmentStartedAt());
        assertNull(transcript.absoluteStartTime());
    }

    @Test
    void getResultAllowsBlankText() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andRespond(withSuccess(completedJson(""), MediaType.APPLICATION_JSON));

        SttJobResultResponse response = client.getResult("python-123");

        assertEquals("", response.result().transcripts().get(0).text());
    }

    @Test
    void getResultMapsPartialWithFailure() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andRespond(withSuccess(partialJson(), MediaType.APPLICATION_JSON));

        SttJobResultResponse response = client.getResult("python-123");

        assertEquals(SttTerminalStatus.PARTIAL, response.result().status());
        assertEquals(1, response.result().transcripts().size());
        assertEquals(1, response.result().failures().size());
        var failure = response.result().failures().get(0);
        assertEquals("session-b", failure.sessionId());
        assertEquals("segment-2", failure.segmentId());
        assertNull(failure.chunkIndex());
        assertEquals("meeting-12/session-b/chunk.wav", failure.filePath());
        assertEquals(0, failure.attempts());
        assertEquals("decode failed", failure.error());
    }

    @Test
    void getResultWrapsHttpError() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123/result"))
            .andRespond(withServerError());

        AiServiceException exception = assertThrows(
            AiServiceException.class,
            () -> client.getResult("python-123")
        );

        assertEquals("Khong the lay ket qua STT", exception.getMessage());
    }

    @Test
    void getStatusMapsProcessingStatus() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andRespond(withSuccess(processingStatusJson(), MediaType.APPLICATION_JSON));

        SttJobStatusResponse response = client.getStatus("python-123").orElseThrow();

        assertEquals("python-123", response.processingId());
        assertEquals(12L, response.meetingId());
        assertEquals("PROCESSING", response.status());
        assertEquals("TRANSCRIBING", response.stage());
        assertEquals(3, response.processedChunks());
        assertEquals(4, response.totalChunks());
        assertEquals(75.0, response.progressPercent());
        assertNull(response.error());
        server.verify();
    }

    @Test
    void getStatusReturnsEmptyForNotFoundRemoteJob() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.getStatus("python-123").isEmpty());
        server.verify();
    }

    @Test
    void getStatusWrapsServerError() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123"))
            .andRespond(withServerError());

        AiServiceException exception = assertThrows(
            AiServiceException.class,
            () -> client.getStatus("python-123")
        );

        assertEquals("Khong the kiem tra STT job", exception.getMessage());
    }

    @Test
    void getStatusMapsCompletedStatus() {
        server.expect(once(), requestTo("http://localhost:8000/api/postprocessing/stt/jobs/python-123"))
            .andRespond(withSuccess(completedStatusJson(), MediaType.APPLICATION_JSON));

        SttJobStatusResponse response = client.getStatus("python-123").orElseThrow();

        assertEquals("python-123", response.processingId());
        assertEquals(12L, response.meetingId());
        assertEquals("COMPLETED", response.status());
        assertEquals("COMPLETED", response.stage());
        assertEquals(4, response.processedChunks());
        assertEquals(4, response.totalChunks());
        assertEquals(100.0, response.progressPercent());
        assertNull(response.error());
    }

    private String completedJson(String text) {
        return """
            {
              "processingId": "python-123",
              "result": {
                "meetingId": 12,
                "sessionIds": ["session-a", "session-b"],
                "status": "COMPLETED",
                "totalManifests": 2,
                "totalSegments": 3,
                "totalChunks": 3,
                "processedChunks": 3,
                "succeededChunks": 3,
                "failedChunks": 0,
                "transcripts": [
                  {
                    "sessionId": "session-a",
                    "attendeeId": "attendee-1",
                    "speakerName": "Nguyen Van A",
                    "segmentId": "segment-1",
                    "segmentIndex": 0,
                    "chunkIndex": 0,
                    "chunkStartMs": 0,
                    "chunkDurationMs": 12345,
                    "segmentStartedAt": "2026-08-26T09:00:00Z",
                    "absoluteStartTime": "2026-08-26T09:00:01Z",
                    "filePath": "meeting-12/session-a/chunk.wav",
                    "text": "%s"
                  },
                  {
                    "sessionId": "session-a",
                    "attendeeId": "attendee-1",
                    "speakerName": "Nguyen Van A",
                    "segmentId": "segment-2",
                    "segmentIndex": 1,
                    "chunkIndex": 1,
                    "chunkStartMs": 12345,
                    "chunkDurationMs": 1000,
                    "segmentStartedAt": "2026-08-26T09:00:13Z",
                    "absoluteStartTime": "2026-08-26T09:00:14Z",
                    "filePath": "meeting-12/session-a/chunk-2.wav",
                    "text": "noi dung 2"
                  },
                  {
                    "sessionId": "session-b",
                    "attendeeId": "attendee-2",
                    "speakerName": "Tran Van B",
                    "segmentId": "segment-3",
                    "segmentIndex": 2,
                    "chunkIndex": 2,
                    "chunkStartMs": 13345,
                    "chunkDurationMs": 1000,
                    "segmentStartedAt": "2026-08-26T09:00:15Z",
                    "absoluteStartTime": "2026-08-26T09:00:16Z",
                    "filePath": "meeting-12/session-b/chunk.wav",
                    "text": "noi dung 3"
                  }
                ],
                "failures": []
              }
            }
            """.formatted(text);
    }

    private String nullableJson() {
        return """
            {
              "processingId": "python-123",
              "result": {
                "meetingId": 12,
                "sessionIds": ["session-a"],
                "status": "COMPLETED",
                "totalManifests": 1,
                "totalSegments": 1,
                "totalChunks": 1,
                "processedChunks": 1,
                "succeededChunks": 1,
                "failedChunks": 0,
                "transcripts": [
                  {
                    "sessionId": "session-a",
                    "attendeeId": null,
                    "speakerName": null,
                    "segmentId": "segment-1",
                    "segmentIndex": 0,
                    "chunkIndex": 0,
                    "chunkStartMs": 0,
                    "chunkDurationMs": 12345,
                    "segmentStartedAt": null,
                    "absoluteStartTime": null,
                    "filePath": "meeting-12/session-a/chunk.wav",
                    "text": "text"
                  }
                ],
                "failures": []
              }
            }
            """;
    }

    private String partialJson() {
        return """
            {
              "processingId": "python-123",
              "result": {
                "meetingId": 12,
                "sessionIds": ["session-a", "session-b"],
                "status": "PARTIAL",
                "totalManifests": 2,
                "totalSegments": 2,
                "totalChunks": 2,
                "processedChunks": 2,
                "succeededChunks": 1,
                "failedChunks": 1,
                "transcripts": [
                  {
                    "sessionId": "session-a",
                    "attendeeId": "attendee-1",
                    "speakerName": "Nguyen Van A",
                    "segmentId": "segment-1",
                    "segmentIndex": 0,
                    "chunkIndex": 0,
                    "chunkStartMs": 0,
                    "chunkDurationMs": 12345,
                    "segmentStartedAt": "2026-08-26T09:00:00Z",
                    "absoluteStartTime": "2026-08-26T09:00:01Z",
                    "filePath": "meeting-12/session-a/chunk.wav",
                    "text": "text"
                  }
                ],
                "failures": [
                  {
                    "sessionId": "session-b",
                    "segmentId": "segment-2",
                    "chunkIndex": null,
                    "filePath": "meeting-12/session-b/chunk.wav",
                    "attempts": 0,
                    "error": "decode failed"
                  }
                ]
              }
            }
            """;
    }

    private String processingStatusJson() {
        return """
            {
              "processingId": "python-123",
              "meetingId": 12,
              "status": "PROCESSING",
              "stage": "TRANSCRIBING",
              "processedChunks": 3,
              "totalChunks": 4,
              "progressPercent": 75.0,
              "error": null
            }
            """;
    }

    private String completedStatusJson() {
        return """
            {
              "processingId": "python-123",
              "meetingId": 12,
              "status": "COMPLETED",
              "stage": "COMPLETED",
              "processedChunks": 4,
              "totalChunks": 4,
              "progressPercent": 100.0,
              "error": null
            }
            """;
    }
}
