package vn.cusc.meetingservice.service;

import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.SttResultValidationException;

@Service
public class MeetingMinutesTranscriptPersistenceService {

    private final MeetingMinutesTranscriptStateService stateService;
    private final MeetingMinutesSttResultFetchService resultFetchService;

    public MeetingMinutesTranscriptPersistenceService(
        MeetingMinutesTranscriptStateService stateService,
        MeetingMinutesSttResultFetchService resultFetchService
    ) {
        this.stateService = stateService;
        this.resultFetchService = resultFetchService;
    }

    public void process(Long jobId) {
        var claimed = stateService.claim(jobId);
        if (claimed.isEmpty()) {
            return;
        }

        try {
            SttMeetingResultResponse result =
                resultFetchService.fetchResult(jobId);
            stateService.persistAndMarkReady(jobId, result);
        } catch (SttResultValidationException exc) {
            stateService.markPermanentFailure(jobId, exc.getMessage());
        } catch (BusinessValidationException exc) {
            stateService.markPermanentFailure(jobId, exc.getMessage());
        } catch (AiServiceException exc) {
            stateService.markRetryableFailure(jobId, exc.getMessage());
        } catch (RuntimeException exc) {
            stateService.markRetryableFailure(jobId, exc.getMessage());
        }
    }
}
