package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.dto.stt.callback.SttProgressCallbackRequest;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MinutesJobNotFoundException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesSttJobStateServiceTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesPreconditionService preconditionService;

    @Test
    void queuedJobClaimMovesToPreparingAudio() {
        MeetingMinutesSttJobStateService service = service();
        MeetingEntity meeting = meeting();
        MeetingMinutesJobEntity job = job(15L, meeting, MinutesJobStatus.QUEUED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));
        when(preconditionService.requireReadyForGeneration(meeting))
            .thenReturn(List.of(capture("manifest-A")));

        Optional<MeetingMinutesSttJobStateService.SubmissionContext> context =
            service.claimForStt(15L);

        assertTrue(context.isPresent());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.PREPARING_AUDIO, job.getStage());
        assertEquals(5, job.getProgressPercent());
        assertNotNull(job.getStartedAt());
    }

    @Test
    void claimReturnsMeetingAndManifestPathsInCaptureOrder() {
        MeetingMinutesSttJobStateService service = service();
        MeetingEntity meeting = meeting();
        MeetingMinutesJobEntity job = job(15L, meeting, MinutesJobStatus.QUEUED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));
        when(preconditionService.requireReadyForGeneration(meeting))
            .thenReturn(List.of(capture("manifest-A"), capture("manifest-B")));

        var context = service.claimForStt(15L).orElseThrow();

        assertEquals(12L, context.meetingId());
        assertEquals(List.of("manifest-A", "manifest-B"), context.manifestPaths());
    }

    @Test
    void claimDeduplicatesManifestPathsAndKeepsOrder() {
        MeetingMinutesSttJobStateService service = service();
        MeetingEntity meeting = meeting();
        MeetingMinutesJobEntity job = job(15L, meeting, MinutesJobStatus.QUEUED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));
        when(preconditionService.requireReadyForGeneration(meeting))
            .thenReturn(List.of(capture("A"), capture("A"), capture("B")));

        var context = service.claimForStt(15L).orElseThrow();

        assertEquals(List.of("A", "B"), context.manifestPaths());
    }

    @Test
    void nonQueuedJobReturnsEmptyContext() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claimForStt(15L).isEmpty());
    }

    @Test
    void markTranscribingUpdatesProcessingState() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.PREPARING_AUDIO);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markTranscribing(15L, "python-123");

        assertEquals("python-123", job.getSttProcessingId());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        assertEquals(10, job.getProgressPercent());
    }

    @Test
    void reclaimStalePreparingAudioReturnsContextWithoutAdvancingState() {
        MeetingMinutesSttJobStateService service = service();
        MeetingEntity meeting = meeting();
        MeetingMinutesJobEntity job = preparingAudioJob(meeting, LocalDateTime.now().minusMinutes(10));
        LocalDateTime originalStartedAt = LocalDateTime.now().minusMinutes(11);
        job.setStartedAt(originalStartedAt);
        job.setAttempt(2);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));
        when(preconditionService.requireReadyForGeneration(meeting))
            .thenReturn(List.of(capture("manifest-A"), capture("manifest-B")));

        Optional<MeetingMinutesSttJobStateService.SubmissionContext> context =
            service.reclaimStalePreparingAudio(15L, LocalDateTime.now().minusMinutes(5));

        assertTrue(context.isPresent());
        assertEquals(15L, context.orElseThrow().jobId());
        assertEquals(12L, context.orElseThrow().meetingId());
        assertEquals(List.of("manifest-A", "manifest-B"), context.orElseThrow().manifestPaths());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.PREPARING_AUDIO, job.getStage());
        assertEquals(null, job.getSttProcessingId());
        assertEquals(2, job.getAttempt());
        assertEquals(originalStartedAt, job.getStartedAt());
        verify(jobRepository).save(job);
    }

    @Test
    void reclaimStalePreparingAudioReturnsEmptyWhenNotStale() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = preparingAudioJob(meeting(), LocalDateTime.now());
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.reclaimStalePreparingAudio(15L, LocalDateTime.now().minusMinutes(5)).isEmpty());

        verify(jobRepository, never()).save(job);
    }

    @Test
    void reclaimStalePreparingAudioReturnsEmptyForWrongStage() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = preparingAudioJob(meeting(), LocalDateTime.now().minusMinutes(10));
        job.setStage(MinutesProcessingStage.TRANSCRIBING);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.reclaimStalePreparingAudio(15L, LocalDateTime.now().minusMinutes(5)).isEmpty());

        verify(jobRepository, never()).save(job);
    }

    @Test
    void reclaimStalePreparingAudioReturnsEmptyWhenProcessingIdExists() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = preparingAudioJob(meeting(), LocalDateTime.now().minusMinutes(10));
        job.setSttProcessingId("python-123");
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.reclaimStalePreparingAudio(15L, LocalDateTime.now().minusMinutes(5)).isEmpty());

        verify(jobRepository, never()).save(job);
    }

    @Test
    void reclaimStalePreparingAudioReturnsEmptyWhenStateChangedBeforeLock() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = preparingAudioJob(meeting(), LocalDateTime.now().minusMinutes(10));
        job.setStatus(MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.reclaimStalePreparingAudio(15L, LocalDateTime.now().minusMinutes(5)).isEmpty());

        verify(jobRepository, never()).save(job);
    }

    @Test
    void markTranscribingDoesNothingWhenAlreadyFailed() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markTranscribing(15L, "python-123");

        assertEquals(null, job.getSttProcessingId());
        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markTranscribingDoesNothingOutsidePreparingAudio() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markTranscribing(15L, "python-123");

        assertEquals(null, job.getSttProcessingId());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markTranscribingDoesNotOverwriteExistingProcessingId() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.PREPARING_AUDIO);
        job.setSttProcessingId("python-original");
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markTranscribing(15L, "python-new");

        assertEquals("python-original", job.getSttProcessingId());
        assertEquals(MinutesProcessingStage.PREPARING_AUDIO, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markFailedUpdatesFailureState() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setProgressPercent(5);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markFailed(15L, "Python error");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertNotNull(job.getFinishedAt());
        assertEquals("Python error", job.getErrorMessage());
        assertEquals(5, job.getProgressPercent());
    }

    @Test
    void markMissingSttJobFailedUpdatesOnlyMissingSttFailureFields() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markMissingSttJobFailed(15L, "python-123");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals(SttTerminalStatus.FAILED, job.getSttStatus());
        assertEquals("Python STT job khong con ton tai", job.getSttErrorMessage());
        assertEquals("Python STT job khong con ton tai", job.getErrorMessage());
        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals("Python STT job khong con ton tai", job.getTranscriptLastError());
        assertEquals(null, job.getTranscriptPersistedAt());
        assertNotNull(job.getFinishedAt());
        assertEquals(55, job.getProgressPercent());
        assertEquals("python-123", job.getSttProcessingId());
    }

    @Test
    void markMissingSttJobFailedDoesNothingWhenAlreadyBuildingTranscript() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markMissingSttJobFailed(15L, "python-123");

        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertEquals(null, job.getSttStatus());
        assertEquals(null, job.getFinishedAt());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markMissingSttJobFailedDoesNothingWhenAlreadyFailed() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        job.setStatus(MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markMissingSttJobFailed(15L, "python-123");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals(null, job.getSttStatus());
        assertEquals(null, job.getFinishedAt());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markMissingSttJobFailedDoesNothingWhenProcessingIdChanged() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        job.setSttProcessingId("python-new");
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markMissingSttJobFailed(15L, "python-old");

        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        assertEquals("python-new", job.getSttProcessingId());
        assertEquals(null, job.getSttStatus());
        assertEquals(null, job.getFinishedAt());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markSttJobObservedAliveTouchesOnlyUpdatedAt() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(originalUpdatedAt);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markSttJobObservedAlive(15L, "python-123");

        assertTrue(job.getUpdatedAt().isAfter(originalUpdatedAt));
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        assertEquals(55, job.getProgressPercent());
        assertEquals("python-123", job.getSttProcessingId());
        assertEquals(null, job.getSttStatus());
        verify(jobRepository).save(job);
    }

    @Test
    void markSttJobObservedAliveDoesNothingWhenAlreadyBuildingTranscript() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(originalUpdatedAt);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markSttJobObservedAlive(15L, "python-123");

        assertEquals(originalUpdatedAt, job.getUpdatedAt());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markSttJobObservedAliveDoesNothingWhenProcessingIdMismatches() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(originalUpdatedAt);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markSttJobObservedAlive(15L, "python-other");

        assertEquals(originalUpdatedAt, job.getUpdatedAt());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markRemoteSttJobFailedUsesRemoteError() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRemoteSttJobFailed(15L, "python-123", "decode failed");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals(SttTerminalStatus.FAILED, job.getSttStatus());
        assertEquals("decode failed", job.getSttErrorMessage());
        assertEquals("decode failed", job.getErrorMessage());
        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals("decode failed", job.getTranscriptLastError());
        assertEquals(null, job.getTranscriptPersistedAt());
        assertNotNull(job.getFinishedAt());
        assertEquals(55, job.getProgressPercent());
        assertEquals("python-123", job.getSttProcessingId());
    }

    @Test
    void markRemoteSttJobFailedUsesFallbackForBlankError() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRemoteSttJobFailed(15L, "python-123", " ");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals(SttTerminalStatus.FAILED, job.getSttStatus());
        assertEquals("Python STT job failed", job.getSttErrorMessage());
        assertEquals("Python STT job failed", job.getErrorMessage());
        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals("Python STT job failed", job.getTranscriptLastError());
        assertEquals(null, job.getTranscriptPersistedAt());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void markRemoteSttJobFailedDoesNothingWhenAlreadyBuildingTranscript() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRemoteSttJobFailed(15L, "python-123", "decode failed");

        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertEquals(null, job.getSttStatus());
        assertEquals(null, job.getFinishedAt());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markRemoteSttJobFailedDoesNothingWhenProcessingIdMismatches() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRemoteSttJobFailed(15L, "python-other", "decode failed");

        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        assertEquals(null, job.getSttStatus());
        assertEquals(null, job.getFinishedAt());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttProgressMapsZeroPercentToTen() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 0, 3, 0.0)
        );

        assertEquals(10, job.getProgressPercent());
    }

    @Test
    void applySttProgressMapsFiftyPercentToForty() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 1, 2, 50.0)
        );

        assertEquals(40, job.getProgressPercent());
    }

    @Test
    void applySttProgressMapsHundredPercentToSeventyWithoutCompleting() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 3, 3, 100.0)
        );

        assertEquals(70, job.getProgressPercent());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
    }

    @Test
    void applySttProgressDoesNotMoveBackward() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(55);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 1, 5, 20.0)
        );

        assertEquals(55, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttProgressIgnoresUnknownProcessingId() {
        MeetingMinutesSttJobStateService service = service();
        when(jobRepository.findBySttProcessingIdForUpdate("python-404"))
            .thenReturn(Optional.empty());

        service.applySttProgress(
            "python-404",
            new SttProgressCallbackRequest(12L, 1, 3, 33.33)
        );

        verify(jobRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    void applySttProgressRejectsMeetingMismatch() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttProgress(
                "python-123",
                new SttProgressCallbackRequest(99L, 1, 3, 33.33)
            )
        );
        assertEquals(10, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttProgressRejectsProcessedChunksGreaterThanTotalChunks() {
        MeetingMinutesSttJobStateService service = service();

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttProgress(
                "python-123",
                new SttProgressCallbackRequest(12L, 4, 3, 33.33)
            )
        );
    }

    @Test
    void applySttProgressIgnoresFailedJob() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        job.setProgressPercent(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 2, 3, 80.0)
        );

        assertEquals(10, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttProgressIgnoresProcessingJobOutsideTranscribingStage() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setProgressPercent(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttProgress(
            "python-123",
            new SttProgressCallbackRequest(12L, 2, 3, 80.0)
        );

        assertEquals(10, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttCompletionPersistsCompletedSummaryAndMovesToBuildingTranscript() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            completedRequest()
        );

        assertEquals(SttTerminalStatus.COMPLETED, job.getSttStatus());
        assertEquals(2, job.getSttTotalManifests());
        assertEquals(3, job.getSttTotalSegments());
        assertEquals(3, job.getSttTotalChunks());
        assertEquals(3, job.getSttProcessedChunks());
        assertEquals(3, job.getSttSucceededChunks());
        assertEquals(0, job.getSttFailedChunks());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertEquals(70, job.getProgressPercent());
        assertEquals(TranscriptPersistenceStatus.PENDING, job.getTranscriptStatus());
        assertEquals(0, job.getTranscriptAttempts());
        assertEquals(null, job.getTranscriptLastError());
        assertEquals(null, job.getTranscriptPersistedAt());
    }

    @Test
    void applySttCompletionRecoversMissingFinalProgress() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(50);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            completedRequest()
        );

        assertEquals(70, job.getProgressPercent());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
    }

    @Test
    void applySttCompletionPersistsPartialSummaryWithoutFailingMinutesJob() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            new SttCompletionCallbackRequest(
                12L,
                SttTerminalStatus.PARTIAL,
                2,
                9,
                10,
                10,
                9,
                1,
                "1 chunk could not be transcribed"
            )
        );

        assertEquals(SttTerminalStatus.PARTIAL, job.getSttStatus());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertEquals(70, job.getProgressPercent());
        assertEquals("1 chunk could not be transcribed", job.getSttErrorMessage());
        assertEquals(TranscriptPersistenceStatus.PENDING, job.getTranscriptStatus());
        assertEquals(0, job.getTranscriptAttempts());
    }

    @Test
    void applySttCompletionFailedZeroCountsMarksMinutesJobFailed() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(10);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            new SttCompletionCallbackRequest(
                12L,
                SttTerminalStatus.FAILED,
                0,
                0,
                0,
                0,
                0,
                0,
                "Manifest not found"
            )
        );

        assertEquals(SttTerminalStatus.FAILED, job.getSttStatus());
        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals("Manifest not found", job.getSttErrorMessage());
        assertEquals("Manifest not found", job.getErrorMessage());
        assertNotNull(job.getFinishedAt());
        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals("Manifest not found", job.getTranscriptLastError());
        assertEquals(null, job.getTranscriptPersistedAt());
    }

    @Test
    void applySttCompletionRejectsUnknownProcessingId() {
        MeetingMinutesSttJobStateService service = service();
        when(jobRepository.findBySttProcessingIdForUpdate("python-404"))
            .thenReturn(Optional.empty());

        assertThrows(
            MinutesJobNotFoundException.class,
            () -> service.applySttCompletion(
                "python-404",
                completedRequest()
            )
        );
    }

    @Test
    void applySttCompletionRejectsMeetingMismatch() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttCompletion(
                "python-123",
                new SttCompletionCallbackRequest(
                    99L,
                    SttTerminalStatus.COMPLETED,
                    2,
                    3,
                    3,
                    3,
                    3,
                    0,
                    null
                )
            )
        );
        assertEquals(MinutesProcessingStage.TRANSCRIBING, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttCompletionRejectsInvalidCompletedCounts() {
        MeetingMinutesSttJobStateService service = service();

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttCompletion(
                "python-123",
                new SttCompletionCallbackRequest(
                    12L,
                    SttTerminalStatus.COMPLETED,
                    2,
                    3,
                    3,
                    3,
                    2,
                    1,
                    null
                )
            )
        );
    }

    @Test
    void applySttCompletionRejectsInvalidPartialCounts() {
        MeetingMinutesSttJobStateService service = service();

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttCompletion(
                "python-123",
                new SttCompletionCallbackRequest(
                    12L,
                    SttTerminalStatus.PARTIAL,
                    2,
                    9,
                    10,
                    9,
                    8,
                    1,
                    null
                )
            )
        );
    }

    @Test
    void applySttCompletionRejectsProcessedChunksGreaterThanTotalChunks() {
        MeetingMinutesSttJobStateService service = service();

        assertThrows(
            BusinessValidationException.class,
            () -> service.applySttCompletion(
                "python-123",
                new SttCompletionCallbackRequest(
                    12L,
                    SttTerminalStatus.FAILED,
                    0,
                    0,
                    3,
                    4,
                    0,
                    0,
                    null
                )
            )
        );
    }

    @Test
    void applySttCompletionDuplicateSameStatusIsIdempotent() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setProgressPercent(70);
        job.setSttProcessingId("python-123");
        job.setSttStatus(SttTerminalStatus.COMPLETED);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            completedRequest()
        );

        assertEquals(SttTerminalStatus.COMPLETED, job.getSttStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertEquals(70, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttCompletionDuplicateSameStatusDoesNotResetReadyTranscript() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setProgressPercent(80);
        job.setSttProcessingId("python-123");
        job.setSttStatus(SttTerminalStatus.COMPLETED);
        job.setTranscriptStatus(TranscriptPersistenceStatus.READY);
        job.setTranscriptAttempts(1);
        job.setTranscriptPersistedAt(LocalDateTime.now());
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        service.applySttCompletion(
            "python-123",
            completedRequest()
        );

        assertEquals(TranscriptPersistenceStatus.READY, job.getTranscriptStatus());
        assertEquals(1, job.getTranscriptAttempts());
        assertEquals(80, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void applySttCompletionRejectsConflictingTerminalStatus() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = transcribingJob(70);
        job.setSttStatus(SttTerminalStatus.COMPLETED);
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        assertThrows(
            DataIntegrityViolationException.class,
            () -> service.applySttCompletion(
                "python-123",
                new SttCompletionCallbackRequest(
                    12L,
                    SttTerminalStatus.PARTIAL,
                    2,
                    9,
                    10,
                    10,
                    9,
                    1,
                    null
                )
            )
        );
        assertEquals(SttTerminalStatus.COMPLETED, job.getSttStatus());
    }

    @Test
    void applySttCompletionRejectsWrongCurrentStateWithoutTerminalStatus() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.GENERATING_MINUTES);
        job.setProgressPercent(80);
        job.setSttProcessingId("python-123");
        when(jobRepository.findBySttProcessingIdForUpdate("python-123"))
            .thenReturn(Optional.of(job));

        assertThrows(
            DataIntegrityViolationException.class,
            () -> service.applySttCompletion(
                "python-123",
                completedRequest()
            )
        );
        assertEquals(MinutesProcessingStage.GENERATING_MINUTES, job.getStage());
        assertEquals(80, job.getProgressPercent());
    }

    @Test
    void requireResultFetchContextReturnsCompletedReadyContext() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.COMPLETED);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        MeetingMinutesSttJobStateService.ResultFetchContext context =
            service.requireResultFetchContext(15L);

        assertEquals(15L, context.jobId());
        assertEquals(12L, context.meetingId());
        assertEquals("python-123", context.processingId());
        assertEquals(SttTerminalStatus.COMPLETED, context.sttStatus());
        assertEquals(2, context.totalManifests());
        assertEquals(3, context.totalSegments());
        assertEquals(3, context.totalChunks());
        assertEquals(3, context.processedChunks());
        assertEquals(3, context.succeededChunks());
        assertEquals(0, context.failedChunks());
    }

    @Test
    void requireResultFetchContextReturnsPartialReadyContext() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.PARTIAL);
        job.setSttTotalSegments(10);
        job.setSttTotalChunks(10);
        job.setSttProcessedChunks(10);
        job.setSttSucceededChunks(9);
        job.setSttFailedChunks(1);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        MeetingMinutesSttJobStateService.ResultFetchContext context =
            service.requireResultFetchContext(15L);

        assertEquals(SttTerminalStatus.PARTIAL, context.sttStatus());
        assertEquals(10, context.processedChunks());
        assertEquals(9, context.succeededChunks());
        assertEquals(1, context.failedChunks());
    }

    @Test
    void requireResultFetchContextRejectsWrongStage() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.COMPLETED);
        job.setStage(MinutesProcessingStage.TRANSCRIBING);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    @Test
    void requireResultFetchContextRejectsWrongJobStatus() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.COMPLETED);
        job.setStatus(MinutesJobStatus.FAILED);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    @Test
    void requireResultFetchContextRejectsMissingSttStatus() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(null);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    @Test
    void requireResultFetchContextRejectsFailedSttStatus() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.FAILED);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    @Test
    void requireResultFetchContextRejectsBlankProcessingId() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.COMPLETED);
        job.setSttProcessingId(" ");
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    @Test
    void requireResultFetchContextRejectsMissingCompletionMetadata() {
        MeetingMinutesSttJobStateService service = service();
        MeetingMinutesJobEntity job = resultReadyJob(SttTerminalStatus.COMPLETED);
        job.setSttTotalChunks(null);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.requireResultFetchContext(15L)
        );
    }

    private MeetingMinutesSttJobStateService service() {
        return new MeetingMinutesSttJobStateService(
            jobRepository,
            preconditionService
        );
    }

    private MeetingEntity meeting() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(12L);
        return meeting;
    }

    private MeetingMinutesJobEntity job(
        Long id,
        MeetingEntity meeting,
        MinutesJobStatus status
    ) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(id);
        job.setMeeting(meeting);
        job.setStatus(status);
        job.setStage(MinutesProcessingStage.VALIDATING);
        job.setProgressPercent(0);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private MeetingMinutesJobEntity transcribingJob(Integer progressPercent) {
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.TRANSCRIBING);
        job.setProgressPercent(progressPercent);
        job.setSttProcessingId("python-123");
        return job;
    }

    private MeetingMinutesJobEntity preparingAudioJob(MeetingEntity meeting, LocalDateTime updatedAt) {
        MeetingMinutesJobEntity job = job(15L, meeting, MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.PREPARING_AUDIO);
        job.setProgressPercent(5);
        job.setAttempt(1);
        job.setUpdatedAt(updatedAt);
        return job;
    }

    private SttCompletionCallbackRequest completedRequest() {
        return new SttCompletionCallbackRequest(
            12L,
            SttTerminalStatus.COMPLETED,
            2,
            3,
            3,
            3,
            3,
            0,
            null
        );
    }

    private MeetingMinutesJobEntity resultReadyJob(SttTerminalStatus sttStatus) {
        MeetingMinutesJobEntity job = job(15L, meeting(), MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setProgressPercent(70);
        job.setSttProcessingId("python-123");
        job.setSttStatus(sttStatus);
        job.setSttTotalManifests(2);
        job.setSttTotalSegments(3);
        job.setSttTotalChunks(3);
        job.setSttProcessedChunks(3);
        job.setSttSucceededChunks(3);
        job.setSttFailedChunks(0);
        return job;
    }

    private MeetingCaptureSessionEntity capture(String manifestPath) {
        MeetingCaptureSessionEntity capture = new MeetingCaptureSessionEntity();
        capture.setManifestPath(manifestPath);
        return capture;
    }
}
