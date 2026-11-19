package vn.cusc.meetingservice.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesJobDispatcherTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesSttSubmissionService submissionService;
    @Mock private MeetingMinutesSttRecoveryService recoveryService;

    @Test
    void dispatchSubmitsOldestQueuedJob() {
        MeetingMinutesJobDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusOrderByCreatedAtAscIdAsc(
            MinutesJobStatus.QUEUED
        )).thenReturn(Optional.of(job));

        dispatcher.dispatchOneQueuedJob();

        verify(submissionService).submit(15L);
    }

    @Test
    void dispatchDoesNothingWhenNoQueuedJob() {
        MeetingMinutesJobDispatcher dispatcher = dispatcher();
        when(jobRepository.findFirstByStatusOrderByCreatedAtAscIdAsc(
            MinutesJobStatus.QUEUED
        )).thenReturn(Optional.empty());

        dispatcher.dispatchOneQueuedJob();

        verify(submissionService, never()).submit(org.mockito.Mockito.anyLong());
    }

    @Test
    void recoverDelegatesToRecoveryService() {
        MeetingMinutesJobDispatcher dispatcher = dispatcher();

        dispatcher.recoverOneStaleTranscribingJob();

        verify(submissionService).recoverOneStalePreparingAudioJob();
        verify(recoveryService).recoverOneStaleTranscribingJob();
    }

    @Test
    void preparingRecoveryFailureDoesNotSkipExistingTranscribingRecovery() {
        MeetingMinutesJobDispatcher dispatcher = dispatcher();
        doThrow(new RuntimeException("prep recovery failure"))
            .when(submissionService).recoverOneStalePreparingAudioJob();

        dispatcher.recoverOneStaleTranscribingJob();

        verify(submissionService).recoverOneStalePreparingAudioJob();
        verify(recoveryService).recoverOneStaleTranscribingJob();
    }

    private MeetingMinutesJobDispatcher dispatcher() {
        return new MeetingMinutesJobDispatcher(
            jobRepository,
            submissionService,
            recoveryService
        );
    }
}
