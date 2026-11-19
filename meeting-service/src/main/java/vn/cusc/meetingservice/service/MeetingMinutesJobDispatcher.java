package vn.cusc.meetingservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@Component
public class MeetingMinutesJobDispatcher {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MeetingMinutesJobDispatcher.class);

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesSttSubmissionService submissionService;
    private final MeetingMinutesSttRecoveryService recoveryService;

    public MeetingMinutesJobDispatcher(
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesSttSubmissionService submissionService,
        MeetingMinutesSttRecoveryService recoveryService
    ) {
        this.jobRepository = jobRepository;
        this.submissionService = submissionService;
        this.recoveryService = recoveryService;
    }

    @Scheduled(
        fixedDelayString =
            "${minutes.processing.dispatch-interval-ms:1000}"
    )
    public void dispatchOneQueuedJob() {
        jobRepository.findFirstByStatusOrderByCreatedAtAscIdAsc(
            MinutesJobStatus.QUEUED
        ).ifPresent(job -> submissionService.submit(job.getId()));
    }

    @Scheduled(
        fixedDelayString =
            "${minutes.processing.stt-recovery-interval-ms:30000}"
    )
    public void recoverOneStaleTranscribingJob() {
        try {
            submissionService.recoverOneStalePreparingAudioJob();
        } catch (RuntimeException exception) {
            LOGGER.warn("Stale PREPARING_AUDIO recovery failed", exception);
        }
        recoveryService.recoverOneStaleTranscribingJob();
    }
}
