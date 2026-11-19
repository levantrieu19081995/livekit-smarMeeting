package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.response.SttFailedChunkResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttTranscriptChunkResponse;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.exception.SttResultValidationException;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService.ResultFetchContext;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesSttResultFetchServiceTest {

    @Mock private MeetingMinutesSttJobStateService stateService;
    @Mock private SttClient sttClient;

    @Test
    void fetchResultReturnsCompletedResult() {
        MeetingMinutesSttResultFetchService service = service();
        ResultFetchContext context = completedContext();
        SttMeetingResultResponse result = completedResult();
        when(stateService.requireResultFetchContext(15L)).thenReturn(context);
        when(sttClient.getResult("python-123"))
            .thenReturn(new SttJobResultResponse("python-123", result));

        SttMeetingResultResponse response = service.fetchResult(15L);

        assertSame(result, response);
        verify(sttClient).getResult("python-123");
    }

    @Test
    void fetchResultReturnsPartialResult() {
        MeetingMinutesSttResultFetchService service = service();
        ResultFetchContext context = partialContext();
        SttMeetingResultResponse result = partialResult();
        when(stateService.requireResultFetchContext(15L)).thenReturn(context);
        when(sttClient.getResult("python-123"))
            .thenReturn(new SttJobResultResponse("python-123", result));

        SttMeetingResultResponse response = service.fetchResult(15L);

        assertSame(result, response);
    }

    @Test
    void fetchResultRejectsProcessingIdMismatch() {
        assertFetchRejects(
            SttResultValidationException.class,
            completedContext(),
            new SttJobResultResponse("python-999", completedResult())
        );
    }

    @Test
    void fetchResultRejectsNullResult() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", null)
        );
    }

    @Test
    void fetchResultRejectsMeetingIdMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", completedResult(99L))
        );
    }

    @Test
    void fetchResultRejectsStatusMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", partialResult())
        );
    }

    @Test
    void fetchResultRejectsTotalManifestsMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", resultWithCounts(1, 3, 3, 3, 3, 0))
        );
    }

    @Test
    void fetchResultRejectsTotalSegmentsMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", resultWithCounts(2, 2, 3, 3, 3, 0))
        );
    }

    @Test
    void fetchResultRejectsTotalChunksMismatch() {
        assertFetchRejects(
            SttResultValidationException.class,
            completedContext(),
            new SttJobResultResponse("python-123", resultWithCounts(2, 3, 4, 3, 3, 0))
        );
    }

    @Test
    void fetchResultRejectsProcessedMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", resultWithCounts(2, 3, 3, 2, 3, 0))
        );
    }

    @Test
    void fetchResultRejectsSucceededMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", resultWithCounts(2, 3, 3, 3, 2, 0))
        );
    }

    @Test
    void fetchResultRejectsFailedMismatch() {
        assertFetchRejects(
            partialContext(),
            new SttJobResultResponse("python-123", resultWithStatusAndCounts(
                SttTerminalStatus.PARTIAL,
                2,
                10,
                10,
                10,
                9,
                0,
                transcripts(9),
                List.of()
            ))
        );
    }

    @Test
    void fetchResultRejectsTranscriptListSizeMismatch() {
        assertFetchRejects(
            completedContext(),
            new SttJobResultResponse("python-123", resultWithStatusAndCounts(
                SttTerminalStatus.COMPLETED,
                2,
                3,
                3,
                3,
                3,
                0,
                transcripts(2),
                List.of()
            ))
        );
    }

    @Test
    void fetchResultRejectsFailureListSizeMismatch() {
        assertFetchRejects(
            partialContext(),
            new SttJobResultResponse("python-123", resultWithStatusAndCounts(
                SttTerminalStatus.PARTIAL,
                2,
                10,
                10,
                10,
                9,
                1,
                transcripts(9),
                List.of()
            ))
        );
    }

    @Test
    void fetchResultAllowsBlankTranscriptText() {
        MeetingMinutesSttResultFetchService service = service();
        ResultFetchContext context = completedContext();
        SttMeetingResultResponse result = resultWithStatusAndCounts(
            SttTerminalStatus.COMPLETED,
            2,
            3,
            3,
            3,
            3,
            0,
            List.of(transcript("")),
            List.of()
        );
        result = new SttMeetingResultResponse(
            result.meetingId(),
            result.sessionIds(),
            result.status(),
            result.totalManifests(),
            result.totalSegments(),
            result.totalChunks(),
            result.processedChunks(),
            result.succeededChunks(),
            result.failedChunks(),
            transcriptsWithBlankFirst(),
            result.failures()
        );
        when(stateService.requireResultFetchContext(15L)).thenReturn(context);
        when(sttClient.getResult("python-123"))
            .thenReturn(new SttJobResultResponse("python-123", result));

        SttMeetingResultResponse response = service.fetchResult(15L);

        assertEquals("", response.transcripts().get(0).text());
    }

    @Test
    void fetchResultPropagatesClientFailure() {
        MeetingMinutesSttResultFetchService service = service();
        when(stateService.requireResultFetchContext(15L)).thenReturn(completedContext());
        when(sttClient.getResult("python-123"))
            .thenThrow(new AiServiceException("Khong the lay ket qua STT"));

        assertThrows(
            AiServiceException.class,
            () -> service.fetchResult(15L)
        );
    }

    private MeetingMinutesSttResultFetchService service() {
        return new MeetingMinutesSttResultFetchService(
            stateService,
            sttClient
        );
    }

    private void assertFetchRejects(
        ResultFetchContext context,
        SttJobResultResponse response
    ) {
        assertFetchRejects(AiServiceException.class, context, response);
    }

    private void assertFetchRejects(
        Class<? extends Throwable> expectedType,
        ResultFetchContext context,
        SttJobResultResponse response
    ) {
        MeetingMinutesSttResultFetchService service = service();
        when(stateService.requireResultFetchContext(15L)).thenReturn(context);
        when(sttClient.getResult("python-123")).thenReturn(response);

        assertThrows(
            expectedType,
            () -> service.fetchResult(15L)
        );
    }

    private ResultFetchContext completedContext() {
        return new ResultFetchContext(
            15L,
            12L,
            "python-123",
            SttTerminalStatus.COMPLETED,
            2,
            3,
            3,
            3,
            3,
            0
        );
    }

    private ResultFetchContext partialContext() {
        return new ResultFetchContext(
            15L,
            12L,
            "python-123",
            SttTerminalStatus.PARTIAL,
            2,
            10,
            10,
            10,
            9,
            1
        );
    }

    private SttMeetingResultResponse completedResult() {
        return completedResult(12L);
    }

    private SttMeetingResultResponse completedResult(Long meetingId) {
        return new SttMeetingResultResponse(
            meetingId,
            List.of("session-a", "session-b"),
            SttTerminalStatus.COMPLETED,
            2,
            3,
            3,
            3,
            3,
            0,
            transcripts(3),
            List.of()
        );
    }

    private SttMeetingResultResponse partialResult() {
        return resultWithStatusAndCounts(
            SttTerminalStatus.PARTIAL,
            2,
            10,
            10,
            10,
            9,
            1,
            transcripts(9),
            List.of(new SttFailedChunkResponse(
                "session-b",
                "segment-failed",
                null,
                "failed.wav",
                0,
                "decode failed"
            ))
        );
    }

    private SttMeetingResultResponse resultWithCounts(
        Integer totalManifests,
        Integer totalSegments,
        Integer totalChunks,
        Integer processedChunks,
        Integer succeededChunks,
        Integer failedChunks
    ) {
        return resultWithStatusAndCounts(
            SttTerminalStatus.COMPLETED,
            totalManifests,
            totalSegments,
            totalChunks,
            processedChunks,
            succeededChunks,
            failedChunks,
            transcripts(succeededChunks),
            List.of()
        );
    }

    private SttMeetingResultResponse resultWithStatusAndCounts(
        SttTerminalStatus status,
        Integer totalManifests,
        Integer totalSegments,
        Integer totalChunks,
        Integer processedChunks,
        Integer succeededChunks,
        Integer failedChunks,
        List<SttTranscriptChunkResponse> transcripts,
        List<SttFailedChunkResponse> failures
    ) {
        return new SttMeetingResultResponse(
            12L,
            List.of("session-a", "session-b"),
            status,
            totalManifests,
            totalSegments,
            totalChunks,
            processedChunks,
            succeededChunks,
            failedChunks,
            transcripts,
            failures
        );
    }

    private List<SttTranscriptChunkResponse> transcripts(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> transcript("text-" + index))
            .toList();
    }

    private List<SttTranscriptChunkResponse> transcriptsWithBlankFirst() {
        return List.of(
            transcript(""),
            transcript("text-1"),
            transcript("text-2")
        );
    }

    private SttTranscriptChunkResponse transcript(String text) {
        return new SttTranscriptChunkResponse(
            "session-a",
            "attendee-1",
            "Nguyen Van A",
            "segment-1",
            0,
            0,
            0L,
            1000L,
            "2026-08-26T09:00:00Z",
            "2026-08-26T09:00:01Z",
            "chunk.wav",
            text
        );
    }
}
