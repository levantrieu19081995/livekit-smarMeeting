package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobStatusResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesSttRecoveryServiceTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private SttClient sttClient;
    @Mock private MeetingMinutesSttJobStateService stateService;

    @Test
    void recoverDoesNothingWhenNoStaleJob() {
        MeetingMinutesSttRecoveryService service = service();
        when(jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any()
        )).thenReturn(Optional.empty());

        service.recoverOneStaleTranscribingJob();

        verify(sttClient, never()).getStatus(any());
    }

    @Test
    void recoverTouchesObservedAliveWhenRemoteQueued() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("QUEUED")));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(stateService, never()).markMissingSttJobFailed(any(), any());
        verify(sttClient, never()).getResult(any());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenRemoteProcessing() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("PROCESSING")));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(stateService, never()).markMissingSttJobFailed(any(), any());
        verify(sttClient, never()).getResult(any());
    }

    @Test
    void recoverMarksMissingWhenRemoteJobIsNotFound() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123")).thenReturn(Optional.empty());

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markMissingSttJobFailed(15L, "python-123");
    }

    @Test
    void recoverCompletedUsesExistingCompletionTransition() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("COMPLETED")));
        when(sttClient.getResult("python-123"))
            .thenReturn(result(SttTerminalStatus.COMPLETED, 0));

        service.recoverOneStaleTranscribingJob();

        ArgumentCaptor<SttCompletionCallbackRequest> captor =
            ArgumentCaptor.forClass(SttCompletionCallbackRequest.class);
        verify(stateService).applySttCompletion(
            org.mockito.Mockito.eq("python-123"),
            captor.capture()
        );
        SttCompletionCallbackRequest request = captor.getValue();
        assertEquals(12L, request.meetingId());
        assertEquals(SttTerminalStatus.COMPLETED, request.status());
        assertEquals(2, request.totalManifests());
        assertEquals(3, request.totalSegments());
        assertEquals(3, request.totalChunks());
        assertEquals(3, request.processedChunks());
        assertEquals(3, request.succeededChunks());
        assertEquals(0, request.failedChunks());
        assertNull(request.errorMessage());
    }

    @Test
    void recoverPartialUsesExistingCompletionTransition() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("PARTIAL")));
        when(sttClient.getResult("python-123"))
            .thenReturn(result(SttTerminalStatus.PARTIAL, 1));

        service.recoverOneStaleTranscribingJob();

        ArgumentCaptor<SttCompletionCallbackRequest> captor =
            ArgumentCaptor.forClass(SttCompletionCallbackRequest.class);
        verify(stateService).applySttCompletion(
            org.mockito.Mockito.eq("python-123"),
            captor.capture()
        );
        SttCompletionCallbackRequest request = captor.getValue();
        assertEquals(SttTerminalStatus.PARTIAL, request.status());
        assertEquals(1, request.failedChunks());
        assertEquals("1 STT chunk failed", request.errorMessage());
    }

    @Test
    void recoverRemoteFailedUsesRemoteFailureWithoutFetchingResult() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("FAILED", "decode failed")));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markRemoteSttJobFailed(15L, "python-123", "decode failed");
        verify(sttClient, never()).getResult(any());
    }

    @Test
    void recoverRemoteFailedWithoutErrorStillMarksRemoteFailure() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("FAILED", null)));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markRemoteSttJobFailed(15L, "python-123", null);
        verify(sttClient, never()).getResult(any());
    }

    @Test
    void recoverLeavesJobUnchangedWhenStatusCheckFails() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenThrow(new AiServiceException("temporary failure"));

        service.recoverOneStaleTranscribingJob();

        verify(stateService, never()).markSttJobObservedAlive(any(), any());
        verify(stateService, never()).markMissingSttJobFailed(any(), any());
        verify(stateService, never()).markRemoteSttJobFailed(any(), any(), any());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenTerminalResultFetchFails() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("COMPLETED")));
        when(sttClient.getResult("python-123"))
            .thenThrow(new AiServiceException("result unavailable"));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(stateService, never()).applySttCompletion(any(), any());
        verify(stateService, never()).markRemoteSttJobFailed(any(), any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenRemoteProcessingIdMismatches() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(new SttJobStatusResponse(
                "python-other",
                12L,
                "COMPLETED",
                "COMPLETED",
                3,
                3,
                100.0,
                null
            )));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(sttClient, never()).getResult(any());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenRemoteMeetingIdMismatches() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(new SttJobStatusResponse(
                "python-123",
                99L,
                "COMPLETED",
                "COMPLETED",
                3,
                3,
                100.0,
                null
            )));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(sttClient, never()).getResult(any());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenResultStatusMismatchesRemoteStatus() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("COMPLETED")));
        when(sttClient.getResult("python-123"))
            .thenReturn(result(SttTerminalStatus.PARTIAL, 1));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    @Test
    void recoverTouchesObservedAliveWhenRemoteStatusIsUnknown() {
        MeetingMinutesSttRecoveryService service = serviceWithJob();
        when(sttClient.getStatus("python-123"))
            .thenReturn(Optional.of(status("PAUSED")));

        service.recoverOneStaleTranscribingJob();

        verify(stateService).markSttJobObservedAlive(15L, "python-123");
        verify(sttClient, never()).getResult(any());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    private MeetingMinutesSttRecoveryService serviceWithJob() {
        MeetingMinutesSttRecoveryService service = service();
        when(jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any()
        )).thenReturn(Optional.of(staleJob()));
        return service;
    }

    private MeetingMinutesSttRecoveryService service() {
        return new MeetingMinutesSttRecoveryService(
            jobRepository,
            sttClient,
            stateService,
            120
        );
    }

    private SttJobStatusResponse status(String status) {
        return status(status, null);
    }

    private SttJobStatusResponse status(String status, String error) {
        return new SttJobStatusResponse(
            "python-123",
            12L,
            status,
            "TRANSCRIBING",
            2,
            3,
            66.0,
            error
        );
    }

    private SttJobResultResponse result(
        SttTerminalStatus status,
        int failedChunks
    ) {
        return new SttJobResultResponse(
            "python-123",
            new SttMeetingResultResponse(
                12L,
                List.of("session-a"),
                status,
                2,
                3,
                3,
                3,
                3 - failedChunks,
                failedChunks,
                List.of(),
                List.of()
            )
        );
    }

    private MeetingMinutesJobEntity staleJob() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(12L);

        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        job.setMeeting(meeting);
        job.setStatus(MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.TRANSCRIBING);
        job.setSttProcessingId("python-123");
        job.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return job;
    }
}
