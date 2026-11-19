package vn.cusc.meetingservice.service;

import java.util.List;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.response.SttFailedChunkResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttTranscriptChunkResponse;
import vn.cusc.meetingservice.exception.SttResultValidationException;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService.ResultFetchContext;

@Service
public class MeetingMinutesSttResultFetchService {

    private final MeetingMinutesSttJobStateService stateService;
    private final SttClient sttClient;

    public MeetingMinutesSttResultFetchService(
        MeetingMinutesSttJobStateService stateService,
        SttClient sttClient
    ) {
        this.stateService = stateService;
        this.sttClient = sttClient;
    }

    public SttMeetingResultResponse fetchResult(Long jobId) {
        ResultFetchContext context =
            stateService.requireResultFetchContext(jobId);

        SttJobResultResponse response =
            sttClient.getResult(context.processingId());

        validateResponse(context, response);

        return response.result();
    }

    private void validateResponse(
        ResultFetchContext context,
        SttJobResultResponse response
    ) {
        if (response == null) {
            throw new SttResultValidationException("Python STT result response is empty");
        }
        if (response.processingId() == null || response.processingId().isBlank()) {
            throw new SttResultValidationException("Python STT result processingId is empty");
        }
        if (!response.processingId().equals(context.processingId())) {
            throw new SttResultValidationException("Python STT result processingId mismatch");
        }
        SttMeetingResultResponse result = response.result();
        if (result == null) {
            throw new SttResultValidationException("Python STT result payload is empty");
        }
        if (!context.meetingId().equals(result.meetingId())) {
            throw new SttResultValidationException("Python STT result meetingId mismatch");
        }
        if (result.status() != context.sttStatus()) {
            throw new SttResultValidationException("Python STT result status mismatch");
        }
        validateCount("totalManifests", context.totalManifests(), result.totalManifests());
        validateCount("totalSegments", context.totalSegments(), result.totalSegments());
        validateCount("totalChunks", context.totalChunks(), result.totalChunks());
        validateCount("processedChunks", context.processedChunks(), result.processedChunks());
        validateCount("succeededChunks", context.succeededChunks(), result.succeededChunks());
        validateCount("failedChunks", context.failedChunks(), result.failedChunks());

        if (size(result.transcripts()) != context.succeededChunks()) {
            throw new SttResultValidationException("Python STT transcript count mismatch");
        }
        if (size(result.failures()) != context.failedChunks()) {
            throw new SttResultValidationException("Python STT failure count mismatch");
        }
    }

    private void validateCount(
        String fieldName,
        Integer expected,
        Integer actual
    ) {
        if (!expected.equals(actual)) {
            throw new SttResultValidationException("Python STT result " + fieldName + " mismatch");
        }
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
