package vn.cusc.meetingservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@Service
public class MeetingMinutesInitialVersionDispatcher {

    private static final Logger log =
        LoggerFactory.getLogger(MeetingMinutesInitialVersionDispatcher.class);

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesInitialVersionService initialVersionService;

    public MeetingMinutesInitialVersionDispatcher(
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesInitialVersionService initialVersionService
    ) {
        this.jobRepository = jobRepository;
        this.initialVersionService = initialVersionService;
    }

    @Scheduled(fixedDelayString = "${minutes.processing.initial-version-dispatch-delay-ms:5000}")
    public void dispatchNext() {
        try {
            jobRepository
                .findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
                    MinutesJobStatus.PROCESSING,
                    MinutesProcessingStage.GENERATING_MINUTES,
                    TranscriptPersistenceStatus.READY
                )
                .map(MeetingMinutesJobEntity::getId)
                .ifPresent(this::createInitialVersion);
        } catch (RuntimeException exception) {
            log.warn("Initial minutes version dispatch failed", exception);
        }
    }

    private void createInitialVersion(Long jobId) {
        try {
            initialVersionService.createInitialVersion(jobId);
        } catch (RuntimeException generationException) {
            log.warn("Initial minutes version generation failed: jobId={}", jobId, generationException);
            try {
                initialVersionService.markGenerationFailure(jobId, generationException.getMessage());
            } catch (RuntimeException markFailureException) {
                log.warn("Initial minutes version failure marking failed: jobId={}", jobId, markFailureException);
            }
        }
    }
}
