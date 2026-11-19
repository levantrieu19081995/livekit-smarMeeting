package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesTranscriptDispatcherTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesTranscriptStateService stateService;
    @Mock private MeetingMinutesTranscriptPersistenceService persistenceService;

    @Test
    void dispatchDoesNothingWhenNoPendingTranscript() {
        MeetingMinutesTranscriptDispatcher dispatcher = dispatcher();
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any(),
            any()
        )).thenReturn(Optional.empty());
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        )).thenReturn(Optional.empty());

        dispatcher.dispatchOnePendingTranscript();

        verify(stateService, never()).recoverStaleProcessing(org.mockito.Mockito.anyLong(), any());
        verify(persistenceService, never()).process(org.mockito.Mockito.anyLong());
    }

    @Test
    void dispatchProcessesOnePendingTranscript() {
        MeetingMinutesTranscriptDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any(),
            any()
        )).thenReturn(Optional.empty());
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        )).thenReturn(Optional.of(job));

        dispatcher.dispatchOnePendingTranscript();

        verify(persistenceService).process(15L);
    }

    @Test
    void dispatchRecoversOneStaleProcessingCandidate() {
        MeetingMinutesTranscriptDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity stale = new MeetingMinutesJobEntity();
        stale.setId(14L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any(),
            any()
        )).thenReturn(Optional.of(stale));
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        )).thenReturn(Optional.empty());

        dispatcher.dispatchOnePendingTranscript();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(stateService).recoverStaleProcessing(org.mockito.Mockito.eq(14L), cutoffCaptor.capture());
        assertTrue(cutoffCaptor.getValue().isBefore(LocalDateTime.now()));
    }

    @Test
    void dispatchProcessesAtMostOnePendingTranscriptPerTick() {
        MeetingMinutesTranscriptDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity pending = new MeetingMinutesJobEntity();
        pending.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any(),
            any()
        )).thenReturn(Optional.empty());
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        )).thenReturn(Optional.of(pending));

        dispatcher.dispatchOnePendingTranscript();

        verify(persistenceService).process(15L);
    }

    @Test
    void dispatchRecoversStaleProcessingBeforePendingProcessing() {
        MeetingMinutesTranscriptDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity stale = new MeetingMinutesJobEntity();
        stale.setId(14L);
        MeetingMinutesJobEntity pending = new MeetingMinutesJobEntity();
        pending.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(),
            any(),
            any()
        )).thenReturn(Optional.of(stale));
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        )).thenReturn(Optional.of(pending));

        dispatcher.dispatchOnePendingTranscript();

        InOrder inOrder = org.mockito.Mockito.inOrder(stateService, persistenceService);
        inOrder.verify(stateService).recoverStaleProcessing(org.mockito.Mockito.eq(14L), any());
        inOrder.verify(persistenceService).process(15L);
    }

    @Test
    void dispatcherRejectsNonPositiveStaleThreshold() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new MeetingMinutesTranscriptDispatcher(jobRepository, stateService, persistenceService, 0));
    }

    private MeetingMinutesTranscriptDispatcher dispatcher() {
        return new MeetingMinutesTranscriptDispatcher(
            jobRepository,
            stateService,
            persistenceService,
            300
        );
    }
}
