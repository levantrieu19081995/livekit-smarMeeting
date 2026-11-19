package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobStatusResponse;
import vn.cusc.meetingservice.dto.stt.response.SttMeetingResultResponse;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.AiServiceException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@Service
public class MeetingMinutesSttRecoveryService {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MeetingMinutesSttRecoveryService.class);

    private final MeetingMinutesJobRepository jobRepository;
    private final SttClient sttClient;
    private final MeetingMinutesSttJobStateService stateService;
    private final long staleAfterSeconds;

    public MeetingMinutesSttRecoveryService(
        MeetingMinutesJobRepository jobRepository,
        SttClient sttClient,
        MeetingMinutesSttJobStateService stateService,
        @Value("${minutes.processing.stt-stale-after-seconds:120}") long staleAfterSeconds
    ) {
        if (staleAfterSeconds < 1) {
            throw new IllegalArgumentException("staleAfterSeconds must be at least 1");
        }
        this.jobRepository = jobRepository;
        this.sttClient = sttClient;
        this.stateService = stateService;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public void recoverOneStaleTranscribingJob() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleAfterSeconds);
        jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.TRANSCRIBING,
            cutoff
        ).ifPresent(this::recover);
    }

    private void recover(MeetingMinutesJobEntity job) {
        Long jobId = job.getId();
        Long meetingId = job.getMeeting() == null ? null : job.getMeeting().getId();
        String processingId = job.getSttProcessingId();

        try {
            Optional<SttJobStatusResponse> optionalRemote =
                sttClient.getStatus(processingId);
            if (optionalRemote.isEmpty()) {
                LOGGER.warn(
                    "STT remote job missing: jobId={}, meetingId={}, processingId={}",
                    jobId,
                    meetingId,
                    processingId
                );
                stateService.markMissingSttJobFailed(jobId, processingId);
                return;
            }

            SttJobStatusResponse remote = optionalRemote.get();
            if (!hasValidIdentity(remote, processingId, meetingId)) {
                LOGGER.warn(
                    "STT recovery status identity mismatch: jobId={}, meetingId={}, processingId={}, remoteMeetingId={}, remoteProcessingId={}",
                    jobId,
                    meetingId,
                    processingId,
                    remote.meetingId(),
                    remote.processingId()
                );
                stateService.markSttJobObservedAlive(jobId, processingId);
                return;
            }

            reconcileRemoteStatus(jobId, meetingId, processingId, remote);
        } catch (AiServiceException exception) {
            LOGGER.warn(
                "STT recovery check failed: jobId={}, processingId={}, error={}",
                jobId,
                processingId,
                exception.getMessage()
            );
        }
    }

    private void reconcileRemoteStatus(
        Long jobId,
        Long meetingId,
        String processingId,
        SttJobStatusResponse remote
    ) {
        String status = remote.status() == null ? null : remote.status().trim();
        if ("QUEUED".equals(status) || "PROCESSING".equals(status)) {
            stateService.markSttJobObservedAlive(jobId, processingId);
            return;
        }
        if ("COMPLETED".equals(status) || "PARTIAL".equals(status)) {
            recoverTerminalSuccess(jobId, meetingId, processingId, status);
            return;
        }
        if ("FAILED".equals(status)) {
            stateService.markRemoteSttJobFailed(jobId, processingId, remote.error());
            return;
        }

        LOGGER.warn(
            "STT recovery ignored unexpected remote status: jobId={}, processingId={}, status={}",
            jobId,
            processingId,
            remote.status()
        );
        stateService.markSttJobObservedAlive(jobId, processingId);
    }

    private void recoverTerminalSuccess(
        Long jobId,
        Long meetingId,
        String processingId,
        String remoteStatus
    ) {
        SttJobResultResponse resultEnvelope;
        try {
            resultEnvelope = sttClient.getResult(processingId);
        } catch (AiServiceException exception) {
            LOGGER.warn(
                "STT recovery result fetch failed: jobId={}, processingId={}, error={}",
                jobId,
                processingId,
                exception.getMessage()
            );
            stateService.markSttJobObservedAlive(jobId, processingId);
            return;
        }

        SttMeetingResultResponse result =
            resultEnvelope == null ? null : resultEnvelope.result();
        if (!isValidTerminalResult(resultEnvelope, result, processingId, meetingId, remoteStatus)) {
            LOGGER.warn(
                "STT recovery ignored invalid terminal result: jobId={}, meetingId={}, processingId={}, remoteStatus={}",
                jobId,
                meetingId,
                processingId,
                remoteStatus
            );
            stateService.markSttJobObservedAlive(jobId, processingId);
            return;
        }

        try {
            stateService.applySttCompletion(
                processingId,
                completionRequest(result)
            );
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "STT recovery completion reconciliation failed: jobId={}, processingId={}, error={}",
                jobId,
                processingId,
                exception.getMessage()
            );
            stateService.markSttJobObservedAlive(jobId, processingId);
        }
    }

    private boolean hasValidIdentity(
        SttJobStatusResponse remote,
        String expectedProcessingId,
        Long expectedMeetingId
    ) {
        return remote.processingId() != null
            && remote.processingId().equals(expectedProcessingId)
            && remote.meetingId() != null
            && remote.meetingId().equals(expectedMeetingId);
    }

    private boolean isValidTerminalResult(
        SttJobResultResponse resultEnvelope,
        SttMeetingResultResponse result,
        String expectedProcessingId,
        Long expectedMeetingId,
        String remoteStatus
    ) {
        return resultEnvelope != null
            && expectedProcessingId.equals(resultEnvelope.processingId())
            && result != null
            && expectedMeetingId.equals(result.meetingId())
            && result.status() != null
            && remoteStatus.equals(result.status().name());
    }

    private SttCompletionCallbackRequest completionRequest(
        SttMeetingResultResponse result
    ) {
        return new SttCompletionCallbackRequest(
            result.meetingId(),
            result.status(),
            result.totalManifests(),
            result.totalSegments(),
            result.totalChunks(),
            result.processedChunks(),
            result.succeededChunks(),
            result.failedChunks(),
            terminalSuccessErrorMessage(result)
        );
    }

    private String terminalSuccessErrorMessage(
        SttMeetingResultResponse result
    ) {
        if (result.status() == SttTerminalStatus.COMPLETED) {
            return null;
        }
        Integer failedChunks = result.failedChunks();
        if (failedChunks != null && failedChunks == 1) {
            return "1 STT chunk failed";
        }
        return failedChunks + " STT chunks failed";
    }
}
