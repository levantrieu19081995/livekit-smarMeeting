package vn.cusc.meetingservice.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.SttResultValidationException;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.service.MeetingMinutesTranscriptStateService.TranscriptClaimContext;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesTranscriptPersistenceServiceTest {

    @Mock private MeetingMinutesTranscriptStateService stateService;
    @Mock private MeetingMinutesSttResultFetchService resultFetchService;

    @Test
    void processDoesNothingWhenClaimIsEmpty() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.empty());

        service.process(15L);

        verify(resultFetchService, never()).fetchResult(15L);
    }

    @Test
    void processFetchesAndPersistsAfterClaim() {
        MeetingMinutesTranscriptPersistenceService service = service();
        SttMeetingResultResponse result = result();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L)).thenReturn(result);

        service.process(15L);

        InOrder order = inOrder(stateService, resultFetchService);
        order.verify(stateService).claim(15L);
        order.verify(resultFetchService).fetchResult(15L);
        order.verify(stateService).persistAndMarkReady(15L, result);
    }

    @Test
    void processMarksPermanentFailureForResultValidationException() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L))
            .thenThrow(new SttResultValidationException("bad result"));

        service.process(15L);

        verify(stateService).markPermanentFailure(15L, "bad result");
        verify(stateService, never()).markRetryableFailure(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any());
    }

    @Test
    void processMarksRetryableFailureForAiServiceException() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L))
            .thenThrow(new AiServiceException("network"));

        service.process(15L);

        verify(stateService).markRetryableFailure(15L, "network");
        verify(stateService, never()).markPermanentFailure(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any());
    }

    @Test
    void processMarksPermanentFailureForBusinessValidationException() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L))
            .thenThrow(new BusinessValidationException("invalid state"));

        service.process(15L);

        verify(stateService).markPermanentFailure(15L, "invalid state");
    }

    @Test
    void processMarksRetryableFailureForRuntimeException() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L))
            .thenThrow(new RuntimeException("db busy"));

        service.process(15L);

        verify(stateService).markRetryableFailure(15L, "db busy");
    }

    @Test
    void processDoesNotEscapeRuntimeException() {
        MeetingMinutesTranscriptPersistenceService service = service();
        when(stateService.claim(15L)).thenReturn(Optional.of(claim()));
        when(resultFetchService.fetchResult(15L))
            .thenThrow(new RuntimeException("db busy"));

        service.process(15L);

        verify(stateService).markRetryableFailure(15L, "db busy");
    }

    private MeetingMinutesTranscriptPersistenceService service() {
        return new MeetingMinutesTranscriptPersistenceService(
            stateService,
            resultFetchService
        );
    }

    private TranscriptClaimContext claim() {
        return new TranscriptClaimContext(15L, 12L, "python-123", 1);
    }

    private SttMeetingResultResponse result() {
        return new SttMeetingResultResponse(
            12L,
            List.of("session-a"),
            SttTerminalStatus.COMPLETED,
            1,
            1,
            0,
            0,
            0,
            0,
            List.of(),
            List.of()
        );
    }
}
