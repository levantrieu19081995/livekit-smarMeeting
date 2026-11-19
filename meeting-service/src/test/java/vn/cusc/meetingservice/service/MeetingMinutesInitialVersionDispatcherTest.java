package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesInitialVersionDispatcherTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesInitialVersionService initialVersionService;

    @Test
    void dispatchesNextGeneratingMinutesJob() {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.of(job));

        dispatcher().dispatchNext();

        verify(initialVersionService).createInitialVersion(15L);
        verify(initialVersionService, never()).markGenerationFailure(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void doesNothingWhenNoJobExists() {
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.empty());

        dispatcher().dispatchNext();

        verify(initialVersionService, never()).createInitialVersion(org.mockito.Mockito.any());
        verify(initialVersionService, never()).markGenerationFailure(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void serviceErrorMarksGenerationFailureWithSameJobId() {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(initialVersionService).createInitialVersion(15L);

        dispatcher().dispatchNext();

        verify(initialVersionService).createInitialVersion(15L);
        verify(initialVersionService).markGenerationFailure(15L, "boom");
    }

    @Test
    void generationFailureDoesNotEscapeDispatcher() {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(initialVersionService).createInitialVersion(15L);

        assertDoesNotThrow(() -> dispatcher().dispatchNext());
    }

    @Test
    void markGenerationFailureFailureDoesNotEscapeDispatcher() {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(initialVersionService).createInitialVersion(15L);
        org.mockito.Mockito.doThrow(new RuntimeException("mark failed"))
            .when(initialVersionService).markGenerationFailure(15L, "boom");

        assertDoesNotThrow(() -> dispatcher().dispatchNext());
        verify(initialVersionService).markGenerationFailure(15L, "boom");
    }

    @Test
    void repositoryLookupFailureDoesNotEscapeDispatcher() {
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenThrow(new RuntimeException("db failure"));

        assertDoesNotThrow(() -> dispatcher().dispatchNext());

        verify(initialVersionService, never()).createInitialVersion(org.mockito.Mockito.any());
        verify(initialVersionService, never()).markGenerationFailure(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void twoTicksMovePastFailedOldestJobAfterFailureMarking() {
        MeetingMinutesInitialVersionDispatcher dispatcher = dispatcher();
        MeetingMinutesJobEntity first = new MeetingMinutesJobEntity();
        first.setId(15L);
        MeetingMinutesJobEntity second = new MeetingMinutesJobEntity();
        second.setId(16L);
        when(jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.GENERATING_MINUTES,
            TranscriptPersistenceStatus.READY
        )).thenReturn(Optional.of(first), Optional.of(second));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(initialVersionService).createInitialVersion(15L);

        dispatcher.dispatchNext();
        dispatcher.dispatchNext();

        InOrder inOrder = org.mockito.Mockito.inOrder(initialVersionService);
        inOrder.verify(initialVersionService).createInitialVersion(15L);
        inOrder.verify(initialVersionService).markGenerationFailure(15L, "boom");
        inOrder.verify(initialVersionService).createInitialVersion(16L);
    }

    private MeetingMinutesInitialVersionDispatcher dispatcher() {
        return new MeetingMinutesInitialVersionDispatcher(
            jobRepository,
            initialVersionService
        );
    }
}
