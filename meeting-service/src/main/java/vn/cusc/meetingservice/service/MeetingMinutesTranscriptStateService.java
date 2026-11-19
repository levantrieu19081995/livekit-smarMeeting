package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.stt.response.SttFailedChunkResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttTranscriptChunkResponse;
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

@Service
public class MeetingMinutesTranscriptStateService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingTranscriptChunkRepository chunkRepository;
    private final MeetingTranscriptFailureRepository failureRepository;
    private final int maxAttempts;

    public MeetingMinutesTranscriptStateService(
        MeetingMinutesJobRepository jobRepository,
        MeetingTranscriptChunkRepository chunkRepository,
        MeetingTranscriptFailureRepository failureRepository,
        @Value("${minutes.processing.transcript-max-attempts:5}") int maxAttempts
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.failureRepository = failureRepository;
        this.maxAttempts = maxAttempts;
    }

    public record TranscriptClaimContext(
        Long jobId,
        Long meetingId,
        String processingId,
        Integer attempt
    ) {
    }

    @Transactional
    public Optional<TranscriptClaimContext> claim(Long jobId) {
        Optional<MeetingMinutesJobEntity> optionalJob =
            jobRepository.findByIdForUpdate(jobId);
        if (optionalJob.isEmpty()) {
            return Optional.empty();
        }

        MeetingMinutesJobEntity job = optionalJob.get();
        if (!isClaimable(job)) {
            return Optional.empty();
        }

        int newAttempt = attempts(job) + 1;
        job.setTranscriptAttempts(newAttempt);
        job.setTranscriptStatus(TranscriptPersistenceStatus.PROCESSING);
        jobRepository.save(job);

        return Optional.of(new TranscriptClaimContext(
            job.getId(),
            job.getMeeting().getId(),
            job.getSttProcessingId(),
            newAttempt
        ));
    }

    @Transactional
    public void persistAndMarkReady(
        Long jobId,
        SttMeetingResultResponse result
    ) {
        MeetingMinutesJobEntity job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new BusinessValidationException("Minutes job not found"));

        if (job.getTranscriptStatus() == TranscriptPersistenceStatus.READY) {
            return;
        }

        validateReadyState(job, result);
        List<SttTranscriptChunkResponse> transcripts =
            requireList(result.transcripts(), job.getSttSucceededChunks(), "transcripts");
        List<SttFailedChunkResponse> failures =
            requireList(result.failures(), job.getSttFailedChunks(), "failures");

        if (job.getSttStatus() == SttTerminalStatus.COMPLETED
            && (!failures.isEmpty() || nonZero(job.getSttFailedChunks()))) {
            throw new BusinessValidationException("COMPLETED STT result must not contain failures");
        }

        List<MeetingTranscriptChunkEntity> chunks =
            mapChunks(job, transcripts);
        List<MeetingTranscriptFailureEntity> failureEntities =
            mapFailures(job, failures);

        chunkRepository.saveAll(chunks);
        failureRepository.saveAll(failureEntities);

        job.setTranscriptStatus(TranscriptPersistenceStatus.READY);
        job.setTranscriptPersistedAt(LocalDateTime.now());
        job.setTranscriptLastError(null);
        job.setStage(MinutesProcessingStage.GENERATING_MINUTES);
        job.setProgressPercent(Math.max(currentProgressOrZero(job), 80));
        jobRepository.save(job);
    }

    @Transactional
    public void markRetryableFailure(
        Long jobId,
        String errorMessage
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (job.getStatus() != MinutesJobStatus.PROCESSING
                || job.getStage() != MinutesProcessingStage.BUILDING_TRANSCRIPT
                || job.getTranscriptStatus() != TranscriptPersistenceStatus.PROCESSING) {
                return;
            }

            String safeMessage = safeErrorMessage(errorMessage);
            job.setTranscriptLastError(safeMessage);
            if (attempts(job) < maxAttempts) {
                job.setTranscriptStatus(TranscriptPersistenceStatus.PENDING);
            } else {
                job.setTranscriptStatus(TranscriptPersistenceStatus.FAILED);
                job.setStatus(MinutesJobStatus.FAILED);
                job.setStage(MinutesProcessingStage.FAILED);
                job.setErrorMessage(safeMessage);
                job.setFinishedAt(LocalDateTime.now());
            }
            jobRepository.save(job);
        });
    }

    @Transactional
    public void recoverStaleProcessing(Long jobId, LocalDateTime cutoff) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (job.getStatus() != MinutesJobStatus.PROCESSING
                || job.getStage() != MinutesProcessingStage.BUILDING_TRANSCRIPT
                || job.getTranscriptStatus() != TranscriptPersistenceStatus.PROCESSING
                || job.getUpdatedAt() == null
                || job.getUpdatedAt().isAfter(cutoff)) {
                return;
            }

            String safeMessage = safeErrorMessage("Recovering stale transcript PROCESSING claim");
            job.setTranscriptLastError(safeMessage);
            if (attempts(job) < maxAttempts) {
                job.setTranscriptStatus(TranscriptPersistenceStatus.PENDING);
            } else {
                job.setTranscriptStatus(TranscriptPersistenceStatus.FAILED);
                job.setStatus(MinutesJobStatus.FAILED);
                job.setStage(MinutesProcessingStage.FAILED);
                job.setErrorMessage(safeMessage);
                job.setFinishedAt(LocalDateTime.now());
            }
            jobRepository.save(job);
        });
    }

    @Transactional
    public void markPermanentFailure(
        Long jobId,
        String errorMessage
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (job.getTranscriptStatus() == TranscriptPersistenceStatus.READY) {
                return;
            }

            String safeMessage = safeErrorMessage(errorMessage);
            job.setTranscriptStatus(TranscriptPersistenceStatus.FAILED);
            job.setTranscriptLastError(safeMessage);
            job.setStatus(MinutesJobStatus.FAILED);
            job.setStage(MinutesProcessingStage.FAILED);
            job.setErrorMessage(safeMessage);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private boolean isClaimable(MeetingMinutesJobEntity job) {
        return job.getStatus() == MinutesJobStatus.PROCESSING
            && job.getStage() == MinutesProcessingStage.BUILDING_TRANSCRIPT
            && job.getTranscriptStatus() == TranscriptPersistenceStatus.PENDING
            && (job.getSttStatus() == SttTerminalStatus.COMPLETED
                || job.getSttStatus() == SttTerminalStatus.PARTIAL)
            && job.getSttProcessingId() != null
            && !job.getSttProcessingId().isBlank();
    }

    private void validateReadyState(
        MeetingMinutesJobEntity job,
        SttMeetingResultResponse result
    ) {
        if (job.getStatus() != MinutesJobStatus.PROCESSING
            || job.getStage() != MinutesProcessingStage.BUILDING_TRANSCRIPT
            || job.getTranscriptStatus() != TranscriptPersistenceStatus.PROCESSING
            || (job.getSttStatus() != SttTerminalStatus.COMPLETED
                && job.getSttStatus() != SttTerminalStatus.PARTIAL)
            || job.getSttProcessingId() == null
            || job.getSttProcessingId().isBlank()) {
            throw new BusinessValidationException("Minutes job is not ready for transcript persistence");
        }
        if (result == null) {
            throw new BusinessValidationException("STT result is required");
        }
        if (!job.getMeeting().getId().equals(result.meetingId())) {
            throw new BusinessValidationException("STT result meetingId mismatch");
        }
        if (result.status() != job.getSttStatus()) {
            throw new BusinessValidationException("STT result status mismatch");
        }
    }

    private <T> List<T> requireList(
        List<T> values,
        Integer expectedCount,
        String fieldName
    ) {
        if (expectedCount == null) {
            throw new BusinessValidationException(fieldName + " expected count is required");
        }
        int expected = expectedCount;
        if (values == null) {
            if (expected == 0) {
                return Collections.emptyList();
            }
            throw new BusinessValidationException(fieldName + " is required");
        }
        if (values.size() != expected) {
            throw new BusinessValidationException(fieldName + " count mismatch");
        }
        return values;
    }

    private List<MeetingTranscriptChunkEntity> mapChunks(
        MeetingMinutesJobEntity job,
        List<SttTranscriptChunkResponse> transcripts
    ) {
        List<MeetingTranscriptChunkEntity> chunks = new ArrayList<>();
        for (int i = 0; i < transcripts.size(); i++) {
            SttTranscriptChunkResponse transcript = transcripts.get(i);
            validateChunk(transcript);

            MeetingTranscriptChunkEntity chunk = new MeetingTranscriptChunkEntity();
            chunk.setMinutesJob(job);
            chunk.setSttProcessingId(job.getSttProcessingId());
            chunk.setResultOrder(i);
            chunk.setSessionId(transcript.sessionId());
            chunk.setAttendeeId(transcript.attendeeId());
            chunk.setSpeakerName(transcript.speakerName());
            chunk.setSegmentId(transcript.segmentId());
            chunk.setSegmentIndex(transcript.segmentIndex());
            chunk.setChunkIndex(transcript.chunkIndex());
            chunk.setChunkStartMs(transcript.chunkStartMs());
            chunk.setChunkDurationMs(transcript.chunkDurationMs());
            chunk.setSegmentStartedAt(transcript.segmentStartedAt());
            chunk.setAbsoluteStartTime(transcript.absoluteStartTime());
            chunk.setFilePath(transcript.filePath());
            chunk.setText(transcript.text());
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<MeetingTranscriptFailureEntity> mapFailures(
        MeetingMinutesJobEntity job,
        List<SttFailedChunkResponse> failures
    ) {
        List<MeetingTranscriptFailureEntity> failureEntities = new ArrayList<>();
        for (int i = 0; i < failures.size(); i++) {
            SttFailedChunkResponse failure = failures.get(i);
            validateFailure(failure);

            MeetingTranscriptFailureEntity entity = new MeetingTranscriptFailureEntity();
            entity.setMinutesJob(job);
            entity.setSttProcessingId(job.getSttProcessingId());
            entity.setFailureOrder(i);
            entity.setSessionId(failure.sessionId());
            entity.setSegmentId(failure.segmentId());
            entity.setChunkIndex(failure.chunkIndex());
            entity.setFilePath(failure.filePath());
            entity.setAttempts(failure.attempts());
            entity.setError(failure.error());
            failureEntities.add(entity);
        }
        return failureEntities;
    }

    private void validateChunk(SttTranscriptChunkResponse transcript) {
        if (transcript == null
            || isBlank(transcript.sessionId())
            || isBlank(transcript.segmentId())
            || isNegativeOrNull(transcript.segmentIndex())
            || isNegativeOrNull(transcript.chunkIndex())
            || isNegativeOrNull(transcript.chunkStartMs())
            || isNegativeOrNull(transcript.chunkDurationMs())
            || isBlank(transcript.filePath())
            || transcript.text() == null) {
            throw new BusinessValidationException("Invalid STT transcript chunk");
        }
    }

    private void validateFailure(SttFailedChunkResponse failure) {
        if (failure == null
            || isBlank(failure.sessionId())
            || isBlank(failure.segmentId())
            || (failure.chunkIndex() != null && failure.chunkIndex() < 0)
            || isBlank(failure.filePath())
            || isNegativeOrNull(failure.attempts())
            || isBlank(failure.error())) {
            throw new BusinessValidationException("Invalid STT transcript failure");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isNegativeOrNull(Integer value) {
        return value == null || value < 0;
    }

    private boolean isNegativeOrNull(Long value) {
        return value == null || value < 0;
    }

    private boolean nonZero(Integer value) {
        return value != null && value != 0;
    }

    private int attempts(MeetingMinutesJobEntity job) {
        return job.getTranscriptAttempts() == null
            ? 0
            : job.getTranscriptAttempts();
    }

    private Integer currentProgressOrZero(MeetingMinutesJobEntity job) {
        return job.getProgressPercent() == null ? 0 : job.getProgressPercent();
    }

    private String safeErrorMessage(String errorMessage) {
        String message = errorMessage == null || errorMessage.isBlank()
            ? "Persist transcript failed"
            : errorMessage;
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
