package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.request.StartSttJobRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobAcceptedResponse;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService.SubmissionContext;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesSttSubmissionServiceTest {

    @Mock private MeetingMinutesSttJobStateService stateService;
    @Mock private SttClient sttClient;
    @Mock private MeetingMinutesJobRepository jobRepository;

    @Test
    void successSubmitsSttJobAndMarksTranscribing() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.of(
            new SubmissionContext(15L, 12L, List.of("A", "B"))
        ));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenReturn(new SttJobAcceptedResponse("python-123", 12L, "QUEUED"));

        service.submit(15L);

        ArgumentCaptor<StartSttJobRequest> requestCaptor =
            ArgumentCaptor.forClass(StartSttJobRequest.class);
        verify(sttClient).startJob(requestCaptor.capture());
        assertEquals("minutes-job-15", requestCaptor.getValue().requestId());
        assertEquals(12L, requestCaptor.getValue().meetingId());
        assertEquals(List.of("A", "B"), requestCaptor.getValue().manifestPaths());
        verify(stateService).markTranscribing(15L, "python-123");
    }

    @Test
    void staleRecoverySendsSameDeterministicRequestIdAndMarksTranscribing() {
        MeetingMinutesSttSubmissionService service = service();
        MeetingMinutesJobEntity candidate = new MeetingMinutesJobEntity();
        candidate.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            org.mockito.Mockito.eq(MinutesJobStatus.PROCESSING),
            org.mockito.Mockito.eq(MinutesProcessingStage.PREPARING_AUDIO),
            org.mockito.Mockito.any()
        )).thenReturn(Optional.of(candidate));
        when(stateService.reclaimStalePreparingAudio(org.mockito.Mockito.eq(15L), org.mockito.Mockito.any()))
            .thenReturn(Optional.of(new SubmissionContext(15L, 12L, List.of("A", "B"))));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenReturn(new SttJobAcceptedResponse("python-123", 12L, "QUEUED"));

        service.recoverOneStalePreparingAudioJob();

        ArgumentCaptor<StartSttJobRequest> requestCaptor =
            ArgumentCaptor.forClass(StartSttJobRequest.class);
        verify(sttClient).startJob(requestCaptor.capture());
        assertEquals("minutes-job-15", requestCaptor.getValue().requestId());
        assertEquals(12L, requestCaptor.getValue().meetingId());
        assertEquals(List.of("A", "B"), requestCaptor.getValue().manifestPaths());
        verify(stateService).markTranscribing(15L, "python-123");
    }

    @Test
    void emptyClaimDoesNotCallPython() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.empty());

        service.submit(15L);

        verify(sttClient, never()).startJob(org.mockito.Mockito.any());
    }

    @Test
    void staleRecoveryDoesNothingWhenNoCandidate() {
        MeetingMinutesSttSubmissionService service = service();
        when(jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            org.mockito.Mockito.eq(MinutesJobStatus.PROCESSING),
            org.mockito.Mockito.eq(MinutesProcessingStage.PREPARING_AUDIO),
            org.mockito.Mockito.any()
        )).thenReturn(Optional.empty());

        service.recoverOneStalePreparingAudioJob();

        verify(sttClient, never()).startJob(org.mockito.Mockito.any());
    }

    @Test
    void staleRecoveryDoesNothingWhenReclaimReturnsEmpty() {
        MeetingMinutesSttSubmissionService service = service();
        MeetingMinutesJobEntity candidate = new MeetingMinutesJobEntity();
        candidate.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            org.mockito.Mockito.eq(MinutesJobStatus.PROCESSING),
            org.mockito.Mockito.eq(MinutesProcessingStage.PREPARING_AUDIO),
            org.mockito.Mockito.any()
        )).thenReturn(Optional.of(candidate));
        when(stateService.reclaimStalePreparingAudio(org.mockito.Mockito.eq(15L), org.mockito.Mockito.any()))
            .thenReturn(Optional.empty());

        service.recoverOneStalePreparingAudioJob();

        verify(sttClient, never()).startJob(org.mockito.Mockito.any());
    }

    @Test
    void sttClientExceptionMarksFailed() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.of(
            new SubmissionContext(15L, 12L, List.of("A"))
        ));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenThrow(new AiServiceException("Python unavailable"));

        service.submit(15L);

        verify(stateService).markFailed(
            org.mockito.Mockito.eq(15L),
            org.mockito.Mockito.contains("Python unavailable")
        );
    }

    @Test
    void nullPythonResponseMarksFailed() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.of(
            new SubmissionContext(15L, 12L, List.of("A"))
        ));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenReturn(null);

        service.submit(15L);

        verify(stateService).markFailed(
            org.mockito.Mockito.eq(15L),
            org.mockito.Mockito.contains("response")
        );
    }

    @Test
    void blankProcessingIdMarksFailed() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.of(
            new SubmissionContext(15L, 12L, List.of("A"))
        ));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenReturn(new SttJobAcceptedResponse(" ", 12L, "QUEUED"));

        service.submit(15L);

        verify(stateService).markFailed(
            org.mockito.Mockito.eq(15L),
            org.mockito.Mockito.contains("processingId")
        );
    }

    @Test
    void mismatchedMeetingIdMarksFailed() {
        MeetingMinutesSttSubmissionService service = service();
        when(stateService.claimForStt(15L)).thenReturn(Optional.of(
            new SubmissionContext(15L, 12L, List.of("A"))
        ));
        when(sttClient.startJob(org.mockito.Mockito.any(StartSttJobRequest.class)))
            .thenReturn(new SttJobAcceptedResponse("python-123", 13L, "QUEUED"));

        service.submit(15L);

        verify(stateService).markFailed(
            org.mockito.Mockito.eq(15L),
            org.mockito.Mockito.contains("mismatch")
        );
    }

    private MeetingMinutesSttSubmissionService service() {
        return new MeetingMinutesSttSubmissionService(
            stateService,
            sttClient,
            jobRepository,
            300
        );
    }
}
