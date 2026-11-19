package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

@Service
public class MeetingMinutesSttJobStateService {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MeetingMinutesSttJobStateService.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final String MISSING_STT_JOB_MESSAGE =
        "Python STT job khong con ton tai";

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesPreconditionService preconditionService;

    public MeetingMinutesSttJobStateService(
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesPreconditionService preconditionService
    ) {
        this.jobRepository = jobRepository;
        this.preconditionService = preconditionService;
    }

    public record SubmissionContext(
        Long jobId,
        Long meetingId,
        List<String> manifestPaths
    ) {
    }

    public record ResultFetchContext(
        Long jobId,
        Long meetingId,
        String processingId,
        SttTerminalStatus sttStatus,
        Integer totalManifests,
        Integer totalSegments,
        Integer totalChunks,
        Integer processedChunks,
        Integer succeededChunks,
        Integer failedChunks
    ) {
    }

    @Transactional
    public Optional<SubmissionContext> claimForStt(Long jobId) {
        Optional<MeetingMinutesJobEntity> optionalJob =
            jobRepository.findByIdForUpdate(jobId);
        if (optionalJob.isEmpty()) {
            return Optional.empty();
        }

        MeetingMinutesJobEntity job = optionalJob.get();
        if (job.getStatus() != MinutesJobStatus.QUEUED) {
            return Optional.empty();
        }

        MeetingEntity meeting = job.getMeeting();
        List<String> manifestPaths = manifestPaths(
            preconditionService.requireReadyForGeneration(meeting)
        );
        if (manifestPaths.isEmpty()) {
            throw new IllegalStateException("Khong co manifest path hop le");
        }

        job.setStatus(MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.PREPARING_AUDIO);
        job.setProgressPercent(5);
        if (job.getStartedAt() == null) {
            job.setStartedAt(LocalDateTime.now());
        }
        job.setErrorMessage(null);
        jobRepository.save(job);

        return Optional.of(new SubmissionContext(
            job.getId(),
            meeting.getId(),
            List.copyOf(manifestPaths)
        ));
    }

    @Transactional
    public void markTranscribing(
        Long jobId,
        String processingId
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (job.getStatus() != MinutesJobStatus.PROCESSING
                || job.getStage() != MinutesProcessingStage.PREPARING_AUDIO
                || job.getSttProcessingId() != null) {
                return;
            }
            job.setSttProcessingId(processingId);
            job.setStatus(MinutesJobStatus.PROCESSING);
            job.setStage(MinutesProcessingStage.TRANSCRIBING);
            job.setProgressPercent(10);
            job.setErrorMessage(null);
            jobRepository.save(job);
        });
    }

    @Transactional
    public Optional<SubmissionContext> reclaimStalePreparingAudio(
        Long jobId,
        LocalDateTime cutoff
    ) {
        Optional<MeetingMinutesJobEntity> optionalJob =
            jobRepository.findByIdForUpdate(jobId);
        if (optionalJob.isEmpty()) {
            return Optional.empty();
        }

        MeetingMinutesJobEntity job = optionalJob.get();
        if (job.getStatus() != MinutesJobStatus.PROCESSING
            || job.getStage() != MinutesProcessingStage.PREPARING_AUDIO
            || job.getSttProcessingId() != null
            || job.getUpdatedAt() == null
            || job.getUpdatedAt().isAfter(cutoff)) {
            return Optional.empty();
        }

        MeetingEntity meeting = job.getMeeting();
        List<String> manifestPaths = manifestPaths(
            preconditionService.requireReadyForGeneration(meeting)
        );
        if (manifestPaths.isEmpty()) {
            throw new IllegalStateException("Khong co manifest path hop le");
        }

        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);

        return Optional.of(new SubmissionContext(
            job.getId(),
            meeting.getId(),
            List.copyOf(manifestPaths)
        ));
    }

    @Transactional
    public void markFailed(
        Long jobId,
        String errorMessage
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            job.setStatus(MinutesJobStatus.FAILED);
            job.setStage(MinutesProcessingStage.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorMessage(safeErrorMessage(errorMessage));
            jobRepository.save(job);
        });
    }

    @Transactional
    public void markMissingSttJobFailed(
        Long jobId,
        String expectedProcessingId
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (!isSameTranscribingJob(job, expectedProcessingId)) {
                return;
            }

            applySttFailure(job, MISSING_STT_JOB_MESSAGE);
            jobRepository.save(job);
        });
    }

    @Transactional
    public void markSttJobObservedAlive(
        Long jobId,
        String expectedProcessingId
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (!isSameTranscribingJob(job, expectedProcessingId)) {
                return;
            }

            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    public void markRemoteSttJobFailed(
        Long jobId,
        String expectedProcessingId,
        String remoteError
    ) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (!isSameTranscribingJob(job, expectedProcessingId)) {
                return;
            }

            String message = remoteError == null || remoteError.isBlank()
                ? "Python STT job failed"
                : safeErrorMessage(remoteError);
            applySttFailure(job, message);
            jobRepository.save(job);
        });
    }

    @Transactional
    public void applySttProgress(
        String processingId,
        SttProgressCallbackRequest request
    ) {
        validateProgressRequest(processingId, request);

        Optional<MeetingMinutesJobEntity> optionalJob =
            jobRepository.findBySttProcessingIdForUpdate(processingId);
        if (optionalJob.isEmpty()) {
            LOGGER.warn(
                "STT progress ignored because job was not found: processingId={}",
                processingId
            );
            return;
        }

        MeetingMinutesJobEntity job = optionalJob.get();
        Long jobMeetingId = job.getMeeting().getId();
        if (!request.meetingId().equals(jobMeetingId)) {
            LOGGER.warn(
                "STT progress rejected because meetingId mismatched: processingId={}, requestMeetingId={}, jobMeetingId={}",
                processingId,
                request.meetingId(),
                jobMeetingId
            );
            throw new BusinessValidationException("STT progress meetingId mismatch");
        }

        if (job.getStatus() != MinutesJobStatus.PROCESSING
            || job.getStage() != MinutesProcessingStage.TRANSCRIBING) {
            LOGGER.warn(
                "STT progress ignored because job state is not transcribing: processingId={}, status={}, stage={}",
                processingId,
                job.getStatus(),
                job.getStage()
            );
            return;
        }

        int mappedProgress = mappedProgress(request.progressPercent());
        Integer current = job.getProgressPercent();
        if (current == null || mappedProgress > current) {
            job.setProgressPercent(mappedProgress);
            jobRepository.save(job);
        }
    }

    @Transactional
    public void applySttCompletion(
        String processingId,
        SttCompletionCallbackRequest request
    ) {
        validateCompletionRequest(processingId, request);

        MeetingMinutesJobEntity job =
            jobRepository.findBySttProcessingIdForUpdate(processingId)
                .orElseThrow(() -> new MinutesJobNotFoundException(null));

        Long jobMeetingId = job.getMeeting().getId();
        if (!request.meetingId().equals(jobMeetingId)) {
            LOGGER.warn(
                "STT completion rejected because meetingId mismatched: processingId={}, requestMeetingId={}, jobMeetingId={}",
                processingId,
                request.meetingId(),
                jobMeetingId
            );
            throw new BusinessValidationException("STT completion meetingId mismatch");
        }

        if (job.getSttStatus() != null) {
            if (job.getSttStatus() == request.status()) {
                return;
            }
            throw new DataIntegrityViolationException("Conflicting STT terminal status");
        }

        if (job.getStatus() != MinutesJobStatus.PROCESSING
            || job.getStage() != MinutesProcessingStage.TRANSCRIBING) {
            throw new DataIntegrityViolationException("STT completion received for invalid job state");
        }

        persistSttSummary(job, request);

        if (request.status() == SttTerminalStatus.FAILED) {
            applyFailedCompletion(job, request);
        } else {
            job.setStatus(MinutesJobStatus.PROCESSING);
            job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
            job.setProgressPercent(Math.max(currentProgressOrZero(job), 70));
            job.setTranscriptStatus(TranscriptPersistenceStatus.PENDING);
            job.setTranscriptAttempts(0);
            job.setTranscriptLastError(null);
            job.setTranscriptPersistedAt(null);
            jobRepository.save(job);
        }
    }

    @Transactional(readOnly = true)
    public ResultFetchContext requireResultFetchContext(
        Long jobId
    ) {
        if (jobId == null) {
            throw new BusinessValidationException("jobId is required");
        }

        MeetingMinutesJobEntity job = jobRepository.findById(jobId)
            .orElseThrow(() -> new MinutesJobNotFoundException(jobId));

        if (job.getStatus() != MinutesJobStatus.PROCESSING
            || job.getStage() != MinutesProcessingStage.BUILDING_TRANSCRIPT) {
            throw new BusinessValidationException("Minutes job is not ready for STT result fetch");
        }
        if (job.getSttStatus() == null || job.getSttStatus() == SttTerminalStatus.FAILED) {
            throw new BusinessValidationException("STT result is not available for this job");
        }
        if (job.getSttProcessingId() == null || job.getSttProcessingId().isBlank()) {
            throw new BusinessValidationException("sttProcessingId is required");
        }
        requireMetadata(job.getSttTotalManifests(), "sttTotalManifests");
        requireMetadata(job.getSttTotalSegments(), "sttTotalSegments");
        requireMetadata(job.getSttTotalChunks(), "sttTotalChunks");
        requireMetadata(job.getSttProcessedChunks(), "sttProcessedChunks");
        requireMetadata(job.getSttSucceededChunks(), "sttSucceededChunks");
        requireMetadata(job.getSttFailedChunks(), "sttFailedChunks");

        return new ResultFetchContext(
            job.getId(),
            job.getMeeting().getId(),
            job.getSttProcessingId(),
            job.getSttStatus(),
            job.getSttTotalManifests(),
            job.getSttTotalSegments(),
            job.getSttTotalChunks(),
            job.getSttProcessedChunks(),
            job.getSttSucceededChunks(),
            job.getSttFailedChunks()
        );
    }

    private List<String> manifestPaths(
        List<MeetingCaptureSessionEntity> captures
    ) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (MeetingCaptureSessionEntity capture : captures) {
            String manifestPath = capture.getManifestPath();
            if (manifestPath != null && !manifestPath.isBlank()) {
                paths.add(manifestPath);
            }
        }
        return new ArrayList<>(paths);
    }

    private String safeErrorMessage(String errorMessage) {
        String message = errorMessage == null || errorMessage.isBlank()
            ? "Xu ly STT that bai"
            : errorMessage;
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private boolean isSameTranscribingJob(
        MeetingMinutesJobEntity job,
        String expectedProcessingId
    ) {
        return job.getStatus() == MinutesJobStatus.PROCESSING
            && job.getStage() == MinutesProcessingStage.TRANSCRIBING
            && expectedProcessingId.equals(job.getSttProcessingId());
    }

    private void applySttFailure(
        MeetingMinutesJobEntity job,
        String message
    ) {
        job.setStatus(MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        job.setSttStatus(SttTerminalStatus.FAILED);
        job.setSttErrorMessage(message);
        job.setTranscriptStatus(TranscriptPersistenceStatus.FAILED);
        job.setTranscriptLastError(message);
        job.setTranscriptPersistedAt(null);
        job.setErrorMessage(message);
        job.setFinishedAt(LocalDateTime.now());
    }

    private void validateProgressRequest(
        String processingId,
        SttProgressCallbackRequest request
    ) {
        if (processingId == null || processingId.isBlank()) {
            throw new BusinessValidationException("processingId is required");
        }
        if (request == null) {
            throw new BusinessValidationException("request is required");
        }
        if (request.meetingId() == null) {
            throw new BusinessValidationException("meetingId is required");
        }
        if (request.processedChunks() == null || request.processedChunks() < 0) {
            throw new BusinessValidationException("processedChunks is invalid");
        }
        if (request.totalChunks() == null || request.totalChunks() < 0) {
            throw new BusinessValidationException("totalChunks is invalid");
        }
        if (request.processedChunks() > request.totalChunks()) {
            throw new BusinessValidationException("processedChunks must not exceed totalChunks");
        }
        if (request.progressPercent() == null
            || request.progressPercent() < 0
            || request.progressPercent() > 100) {
            throw new BusinessValidationException("progressPercent is invalid");
        }
    }

    private void validateCompletionRequest(
        String processingId,
        SttCompletionCallbackRequest request
    ) {
        if (processingId == null || processingId.isBlank()) {
            throw new BusinessValidationException("processingId is required");
        }
        if (request == null) {
            throw new BusinessValidationException("request is required");
        }
        if (request.meetingId() == null) {
            throw new BusinessValidationException("meetingId is required");
        }
        if (request.status() == null) {
            throw new BusinessValidationException("status is required");
        }
        validateNonNegative(request.totalManifests(), "totalManifests");
        validateNonNegative(request.totalSegments(), "totalSegments");
        validateNonNegative(request.totalChunks(), "totalChunks");
        validateNonNegative(request.processedChunks(), "processedChunks");
        validateNonNegative(request.succeededChunks(), "succeededChunks");
        validateNonNegative(request.failedChunks(), "failedChunks");

        if (request.processedChunks() > request.totalChunks()) {
            throw new BusinessValidationException("processedChunks must not exceed totalChunks");
        }
        if (request.succeededChunks() + request.failedChunks() > request.processedChunks()) {
            throw new BusinessValidationException("chunk summary exceeds processedChunks");
        }

        if (request.status() == SttTerminalStatus.COMPLETED) {
            validateCompletedCompletion(request);
        }
        if (request.status() == SttTerminalStatus.PARTIAL) {
            validatePartialCompletion(request);
        }
    }

    private void validateNonNegative(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw new BusinessValidationException(fieldName + " is invalid");
        }
    }

    private void requireMetadata(Integer value, String fieldName) {
        if (value == null) {
            throw new BusinessValidationException(fieldName + " is required");
        }
    }

    private void validateCompletedCompletion(
        SttCompletionCallbackRequest request
    ) {
        if (!request.processedChunks().equals(request.totalChunks())
            || request.failedChunks() != 0
            || !request.succeededChunks().equals(request.processedChunks())) {
            throw new BusinessValidationException("Invalid COMPLETED STT summary");
        }
    }

    private void validatePartialCompletion(
        SttCompletionCallbackRequest request
    ) {
        if (!request.processedChunks().equals(request.totalChunks())
            || request.succeededChunks() <= 0
            || request.failedChunks() <= 0
            || request.succeededChunks() + request.failedChunks() != request.processedChunks()) {
            throw new BusinessValidationException("Invalid PARTIAL STT summary");
        }
    }

    private void persistSttSummary(
        MeetingMinutesJobEntity job,
        SttCompletionCallbackRequest request
    ) {
        job.setSttStatus(request.status());
        job.setSttTotalManifests(request.totalManifests());
        job.setSttTotalSegments(request.totalSegments());
        job.setSttTotalChunks(request.totalChunks());
        job.setSttProcessedChunks(request.processedChunks());
        job.setSttSucceededChunks(request.succeededChunks());
        job.setSttFailedChunks(request.failedChunks());
        job.setSttErrorMessage(normalizedErrorMessage(request.errorMessage()));
    }

    private void applyFailedCompletion(
        MeetingMinutesJobEntity job,
        SttCompletionCallbackRequest request
    ) {
        job.setStatus(MinutesJobStatus.FAILED);
        job.setStage(MinutesProcessingStage.FAILED);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorMessage(safeErrorMessage(
            normalizedErrorMessage(request.errorMessage()) != null
                ? normalizedErrorMessage(request.errorMessage())
                : "STT processing failed"
        ));
        job.setTranscriptStatus(TranscriptPersistenceStatus.FAILED);
        job.setTranscriptLastError(job.getErrorMessage());
        job.setTranscriptPersistedAt(null);
        jobRepository.save(job);
    }

    private Integer currentProgressOrZero(MeetingMinutesJobEntity job) {
        return job.getProgressPercent() == null ? 0 : job.getProgressPercent();
    }

    private String normalizedErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        return errorMessage.trim();
    }

    private int mappedProgress(Double progressPercent) {
        int mappedProgress = 10 + (int) Math.round(progressPercent * 0.60);
        return Math.max(10, Math.min(70, mappedProgress));
    }
}
