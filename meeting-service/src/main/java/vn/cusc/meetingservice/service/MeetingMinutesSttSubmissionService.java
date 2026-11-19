package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.client.stt.SttClient;
import vn.cusc.meetingservice.dto.stt.request.StartSttJobRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobAcceptedResponse;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService.SubmissionContext;

@Service
public class MeetingMinutesSttSubmissionService {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MeetingMinutesSttSubmissionService.class);

    private final MeetingMinutesSttJobStateService stateService;
    private final SttClient sttClient;
    private final MeetingMinutesJobRepository jobRepository;
    private final long sttSubmissionStaleAfterSeconds;

    public MeetingMinutesSttSubmissionService(
        MeetingMinutesSttJobStateService stateService,
        SttClient sttClient,
        MeetingMinutesJobRepository jobRepository,
        @Value("${minutes.processing.stt-submission-stale-after-seconds:300}") long sttSubmissionStaleAfterSeconds
    ) {
        if (sttSubmissionStaleAfterSeconds < 1) {
            throw new IllegalArgumentException("sttSubmissionStaleAfterSeconds must be at least 1");
        }
        this.stateService = stateService;
        this.sttClient = sttClient;
        this.jobRepository = jobRepository;
        this.sttSubmissionStaleAfterSeconds = sttSubmissionStaleAfterSeconds;
    }

    public void submit(Long jobId) {
        SubmissionContext context;
        try {
            context = stateService.claimForStt(jobId).orElse(null);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            LOGGER.error(
                "STT job claim failed: jobId={}, error={}",
                jobId,
                message
            );
            stateService.markFailed(jobId, message);
            return;
        }

        if (context == null) {
            return;
        }

        submitContext(context);
    }

    public void recoverOneStalePreparingAudioJob() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(sttSubmissionStaleAfterSeconds);
        jobRepository.findFirstByStatusAndStageAndSttProcessingIdIsNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.PREPARING_AUDIO,
            cutoff
        ).ifPresent(job -> recover(job, cutoff));
    }

    private void recover(
        MeetingMinutesJobEntity job,
        LocalDateTime cutoff
    ) {
        SubmissionContext context;
        try {
            context = stateService.reclaimStalePreparingAudio(job.getId(), cutoff).orElse(null);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            LOGGER.error(
                "Stale PREPARING_AUDIO reclaim failed: jobId={}, error={}",
                job.getId(),
                message
            );
            stateService.markFailed(job.getId(), message);
            return;
        }

        if (context == null) {
            return;
        }

        submitContext(context);
    }

    private void submitContext(
        SubmissionContext context
    ) {
        try {
            SttJobAcceptedResponse response = sttClient.startJob(
                new StartSttJobRequest(
                    requestId(context.jobId()),
                    context.meetingId(),
                    context.manifestPaths()
                )
            );
            validateResponse(context, response);
            stateService.markTranscribing(context.jobId(), response.processingId());
            LOGGER.info(
                "STT job submitted: jobId={}, meetingId={}, manifestCount={}, processingId={}",
                context.jobId(),
                context.meetingId(),
                context.manifestPaths().size(),
                response.processingId()
            );
        } catch (Exception exception) {
            String message = safeMessage(exception);
            LOGGER.error(
                "STT job submission failed: jobId={}, meetingId={}, error={}",
                context.jobId(),
                context.meetingId(),
                message
            );
            stateService.markFailed(context.jobId(), message);
        }
    }

    private String requestId(Long jobId) {
        return "minutes-job-" + jobId;
    }

    private void validateResponse(
        SubmissionContext context,
        SttJobAcceptedResponse response
    ) {
        if (response == null) {
            throw new IllegalStateException("Python STT response is empty");
        }
        if (response.processingId() == null || response.processingId().isBlank()) {
            throw new IllegalStateException("Python STT processingId is empty");
        }
        if (response.meetingId() == null) {
            throw new IllegalStateException("Python STT meetingId is empty");
        }
        if (!response.meetingId().equals(context.meetingId())) {
            throw new IllegalStateException("Python STT meetingId mismatch");
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
