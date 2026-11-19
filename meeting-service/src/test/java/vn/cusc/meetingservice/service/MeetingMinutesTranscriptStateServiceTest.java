package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
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
import vn.cusc.meetingservice.dto.stt.response.SttFailedChunkResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttTranscriptChunkResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.entity.MeetingTranscriptChunkEntity;
import vn.cusc.meetingservice.entity.MeetingTranscriptFailureEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingTranscriptChunkRepository;
import vn.cusc.meetingservice.repository.MeetingTranscriptFailureRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesTranscriptStateServiceTest {

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingTranscriptChunkRepository chunkRepository;
    @Mock private MeetingTranscriptFailureRepository failureRepository;

    @Test
    void claimPendingCompletedJob() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.COMPLETED);
        job.setTranscriptAttempts(0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        var context = service.claim(15L).orElseThrow();

        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        assertEquals(1, job.getTranscriptAttempts());
        assertEquals(15L, context.jobId());
        assertEquals(12L, context.meetingId());
        assertEquals("python-123", context.processingId());
        assertEquals(1, context.attempt());
    }

    @Test
    void claimPendingPartialJob() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.PARTIAL);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claim(15L).isPresent());
    }

    @Test
    void claimReadyJobReturnsEmpty() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.COMPLETED);
        job.setTranscriptStatus(TranscriptPersistenceStatus.READY);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claim(15L).isEmpty());
    }

    @Test
    void claimWrongStageReturnsEmpty() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.COMPLETED);
        job.setStage(MinutesProcessingStage.TRANSCRIBING);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claim(15L).isEmpty());
    }

    @Test
    void claimFailedSttReturnsEmpty() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.FAILED);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claim(15L).isEmpty());
    }

    @Test
    void claimBlankProcessingIdReturnsEmpty() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = readyToClaim(SttTerminalStatus.COMPLETED);
        job.setSttProcessingId(" ");
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertTrue(service.claim(15L).isEmpty());
    }

    @Test
    void persistCompletedResultMarksReady() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 3, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(15L, completedResult(transcripts(3), List.of()));

        assertEquals(TranscriptPersistenceStatus.READY, job.getTranscriptStatus());
        assertEquals(80, job.getProgressPercent());
        assertEquals(MinutesProcessingStage.GENERATING_MINUTES, job.getStage());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertNotNull(job.getTranscriptPersistedAt());
        assertEquals(null, job.getTranscriptLastError());
        verify(chunkRepository).saveAll(org.mockito.Mockito.argThat(values -> iterableSize(values) == 3));
        verify(failureRepository).saveAll(org.mockito.Mockito.argThat(values -> iterableSize(values) == 0));
    }

    @Test
    void persistPartialResultPersistsFailuresAndKeepsMinutesProcessing() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.PARTIAL, 9, 1);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(
            15L,
            partialResult(transcripts(9), List.of(failure(null)))
        );

        assertEquals(TranscriptPersistenceStatus.READY, job.getTranscriptStatus());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        verify(chunkRepository).saveAll(org.mockito.Mockito.argThat(values -> iterableSize(values) == 9));
        verify(failureRepository).saveAll(org.mockito.Mockito.argThat(values -> iterableSize(values) == 1));
    }

    @Test
    void persistAllowsEmptyText() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(15L, completedResult(List.of(transcript("")), List.of()));

        List<MeetingTranscriptChunkEntity> chunks = capturedChunks();
        assertEquals("", chunks.get(0).getText());
    }

    @Test
    void persistAllowsNullableOptionalTranscriptFields() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(
            15L,
            completedResult(List.of(new SttTranscriptChunkResponse(
                "session-a",
                null,
                null,
                "segment-1",
                0,
                0,
                0L,
                1000L,
                null,
                null,
                "chunk.wav",
                "hello"
            )), List.of())
        );

        MeetingTranscriptChunkEntity chunk = capturedChunks().get(0);
        assertEquals(null, chunk.getAttendeeId());
        assertEquals(null, chunk.getSpeakerName());
        assertEquals(null, chunk.getSegmentStartedAt());
        assertEquals(null, chunk.getAbsoluteStartTime());
    }

    @Test
    void persistPreservesTimestampStringsExactly() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(
            15L,
            completedResult(List.of(new SttTranscriptChunkResponse(
                "session-a",
                "attendee-1",
                "Speaker",
                "segment-1",
                0,
                0,
                0L,
                1000L,
                "2026-08-26T08:00:00Z",
                "2026-08-26T15:00:00+07:00",
                "chunk.wav",
                "hello"
            )), List.of())
        );

        MeetingTranscriptChunkEntity chunk = capturedChunks().get(0);
        assertEquals("2026-08-26T08:00:00Z", chunk.getSegmentStartedAt());
        assertEquals("2026-08-26T15:00:00+07:00", chunk.getAbsoluteStartTime());
    }

    @Test
    void persistUsesTranscriptResultOrder() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 3, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(15L, completedResult(transcripts(3), List.of()));

        List<MeetingTranscriptChunkEntity> chunks = capturedChunks();
        assertEquals(0, chunks.get(0).getResultOrder());
        assertEquals(1, chunks.get(1).getResultOrder());
        assertEquals(2, chunks.get(2).getResultOrder());
    }

    @Test
    void persistRejectsInvalidChunkIndex() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.persistAndMarkReady(
                15L,
                completedResult(List.of(transcriptWithChunkIndex(-1)), List.of())
            )
        );
        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        verify(chunkRepository, never()).saveAll(anyList());
    }

    @Test
    void persistRejectsNullText() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        assertThrows(
            BusinessValidationException.class,
            () -> service.persistAndMarkReady(15L, completedResult(List.of(transcript(null)), List.of()))
        );
    }

    @Test
    void persistAllowsBlankText() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(15L, completedResult(List.of(transcript("")), List.of()));

        assertEquals("", capturedChunks().get(0).getText());
    }

    @Test
    void persistPartialAllowsFailureNullChunkIndex() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.PARTIAL, 1, 1);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(
            15L,
            partialResult(List.of(transcript("text")), List.of(failure(null)))
        );

        assertEquals(null, capturedFailures().get(0).getChunkIndex());
    }

    @Test
    void markRetryableFailureBelowMaxMovesBackToPending() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        job.setTranscriptAttempts(2);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRetryableFailure(15L, "network");

        assertEquals(TranscriptPersistenceStatus.PENDING, job.getTranscriptStatus());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
    }

    @Test
    void markRetryableFailureAtMaxFailsMinutesJob() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        job.setTranscriptAttempts(3);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markRetryableFailure(15L, "network");

        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void recoverStaleProcessingBelowMaxAttemptsMovesBackToPending() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = staleProcessingJob();
        job.setTranscriptAttempts(2);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(cutoff.minusSeconds(1));
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.recoverStaleProcessing(15L, cutoff);

        assertEquals(TranscriptPersistenceStatus.PENDING, job.getTranscriptStatus());
        assertEquals(2, job.getTranscriptAttempts());
        assertEquals("python-123", job.getSttProcessingId());
        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        assertNotNull(job.getTranscriptLastError());
    }

    @Test
    void recoverStaleProcessingAtMaxAttemptsFailsMinutesJob() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = staleProcessingJob();
        job.setTranscriptAttempts(3);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(cutoff);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.recoverStaleProcessing(15L, cutoff);

        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertNotNull(job.getFinishedAt());
        assertNotNull(job.getErrorMessage());
        assertEquals(3, job.getTranscriptAttempts());
        assertEquals("python-123", job.getSttProcessingId());
    }

    @Test
    void recoverStaleProcessingNewerThanCutoffDoesNotChangeState() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = staleProcessingJob();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(cutoff.plusSeconds(1));
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.recoverStaleProcessing(15L, cutoff);

        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void recoverStaleProcessingWrongStageDoesNotChangeState() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = staleProcessingJob();
        job.setStage(MinutesProcessingStage.GENERATING_MINUTES);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(cutoff.minusSeconds(1));
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.recoverStaleProcessing(15L, cutoff);

        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void recoverStaleProcessingWrongJobStatusDoesNotChangeState() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = staleProcessingJob();
        job.setStatus(MinutesJobStatus.QUEUED);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        job.setUpdatedAt(cutoff.minusSeconds(1));
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.recoverStaleProcessing(15L, cutoff);

        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void recoverStaleProcessingDoesNotOverwriteNonProcessingTranscriptStatus() {
        for (TranscriptPersistenceStatus status : List.of(
            TranscriptPersistenceStatus.READY,
            TranscriptPersistenceStatus.PENDING,
            TranscriptPersistenceStatus.FAILED
        )) {
            org.mockito.Mockito.reset(jobRepository);
            MeetingMinutesTranscriptStateService service = service();
            MeetingMinutesJobEntity job = staleProcessingJob();
            job.setTranscriptStatus(status);
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
            job.setUpdatedAt(cutoff.minusSeconds(1));
            when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

            service.recoverStaleProcessing(15L, cutoff);

            assertEquals(status, job.getTranscriptStatus());
            verify(jobRepository, never()).save(job);
        }
    }

    @Test
    void recoverStaleProcessingCandidateDisappearedBeforeLockIsNoOp() {
        MeetingMinutesTranscriptStateService service = service();
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.empty());

        service.recoverStaleProcessing(15L, LocalDateTime.now().minusMinutes(5));

        verify(jobRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    void markPermanentFailureFailsImmediately() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.markPermanentFailure(15L, "bad result");

        assertEquals(TranscriptPersistenceStatus.FAILED, job.getTranscriptStatus());
        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals("bad result", job.getErrorMessage());
    }

    @Test
    void persistAlreadyReadyDoesNotInsertAgain() {
        MeetingMinutesTranscriptStateService service = service();
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        job.setTranscriptStatus(TranscriptPersistenceStatus.READY);
        when(jobRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(job));

        service.persistAndMarkReady(15L, completedResult(List.of(transcript("text")), List.of()));

        verify(chunkRepository, never()).saveAll(anyList());
        verify(failureRepository, never()).saveAll(anyList());
    }

    private MeetingMinutesTranscriptStateService service() {
        return new MeetingMinutesTranscriptStateService(
            jobRepository,
            chunkRepository,
            failureRepository,
            3
        );
    }

    private MeetingMinutesJobEntity readyToClaim(SttTerminalStatus sttStatus) {
        MeetingMinutesJobEntity job = baseJob();
        job.setTranscriptStatus(TranscriptPersistenceStatus.PENDING);
        job.setSttStatus(sttStatus);
        job.setTranscriptAttempts(0);
        return job;
    }

    private MeetingMinutesJobEntity processingTranscriptJob(
        SttTerminalStatus status,
        int succeededChunks,
        int failedChunks
    ) {
        MeetingMinutesJobEntity job = baseJob();
        job.setTranscriptStatus(TranscriptPersistenceStatus.PROCESSING);
        job.setSttStatus(status);
        job.setSttSucceededChunks(succeededChunks);
        job.setSttFailedChunks(failedChunks);
        job.setTranscriptAttempts(1);
        return job;
    }

    private MeetingMinutesJobEntity staleProcessingJob() {
        MeetingMinutesJobEntity job = processingTranscriptJob(SttTerminalStatus.COMPLETED, 1, 0);
        job.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        return job;
    }

    private MeetingMinutesJobEntity baseJob() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(12L);

        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(15L);
        job.setMeeting(meeting);
        job.setStatus(MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setProgressPercent(70);
        job.setSttProcessingId("python-123");
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private SttMeetingResultResponse completedResult(
        List<SttTranscriptChunkResponse> transcripts,
        List<SttFailedChunkResponse> failures
    ) {
        return result(SttTerminalStatus.COMPLETED, transcripts, failures);
    }

    private SttMeetingResultResponse partialResult(
        List<SttTranscriptChunkResponse> transcripts,
        List<SttFailedChunkResponse> failures
    ) {
        return result(SttTerminalStatus.PARTIAL, transcripts, failures);
    }

    private SttMeetingResultResponse result(
        SttTerminalStatus status,
        List<SttTranscriptChunkResponse> transcripts,
        List<SttFailedChunkResponse> failures
    ) {
        return new SttMeetingResultResponse(
            12L,
            List.of("session-a"),
            status,
            1,
            1,
            (transcripts == null ? 0 : transcripts.size()) + (failures == null ? 0 : failures.size()),
            (transcripts == null ? 0 : transcripts.size()) + (failures == null ? 0 : failures.size()),
            transcripts == null ? 0 : transcripts.size(),
            failures == null ? 0 : failures.size(),
            transcripts,
            failures
        );
    }

    private List<SttTranscriptChunkResponse> transcripts(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> new SttTranscriptChunkResponse(
                "session-a",
                "attendee-" + index,
                "Speaker " + index,
                "segment-" + index,
                index,
                index,
                (long) index,
                1000L,
                "2026-08-26T08:00:00Z",
                "2026-08-26T15:00:00+07:00",
                "chunk-" + index + ".wav",
                "text-" + index
            ))
            .toList();
    }

    private SttTranscriptChunkResponse transcript(String text) {
        return new SttTranscriptChunkResponse(
            "session-a",
            "attendee-1",
            "Speaker",
            "segment-1",
            0,
            0,
            0L,
            1000L,
            "2026-08-26T08:00:00Z",
            "2026-08-26T15:00:00+07:00",
            "chunk.wav",
            text
        );
    }

    private SttTranscriptChunkResponse transcriptWithChunkIndex(Integer chunkIndex) {
        return new SttTranscriptChunkResponse(
            "session-a",
            "attendee-1",
            "Speaker",
            "segment-1",
            0,
            chunkIndex,
            0L,
            1000L,
            "2026-08-26T08:00:00Z",
            "2026-08-26T15:00:00+07:00",
            "chunk.wav",
            "text"
        );
    }

    private SttFailedChunkResponse failure(Integer chunkIndex) {
        return new SttFailedChunkResponse(
            "session-a",
            "segment-failed",
            chunkIndex,
            "failed.wav",
            2,
            "decode failed"
        );
    }

    private List<MeetingTranscriptChunkEntity> capturedChunks() {
        ArgumentCaptor<List<MeetingTranscriptChunkEntity>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private List<MeetingTranscriptFailureEntity> capturedFailures() {
        ArgumentCaptor<List<MeetingTranscriptFailureEntity>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(failureRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private int iterableSize(Iterable<?> values) {
        int size = 0;
        for (Object ignored : values) {
            size++;
        }
        return size;
    }
}
