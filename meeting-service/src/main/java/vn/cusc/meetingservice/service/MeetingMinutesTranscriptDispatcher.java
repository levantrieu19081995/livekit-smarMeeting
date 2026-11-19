package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;

@Component
public class MeetingMinutesTranscriptDispatcher {

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesTranscriptStateService stateService;
    private final MeetingMinutesTranscriptPersistenceService persistenceService;
    private final long transcriptStaleAfterSeconds;

    public MeetingMinutesTranscriptDispatcher(
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesTranscriptStateService stateService,
        MeetingMinutesTranscriptPersistenceService persistenceService,
        @Value("${minutes.processing.transcript-stale-after-seconds:300}") long transcriptStaleAfterSeconds
    ) {
        if (transcriptStaleAfterSeconds <= 0) {
            throw new IllegalArgumentException("transcriptStaleAfterSeconds must be positive");
        }
        this.jobRepository = jobRepository;
        this.stateService = stateService;
        this.persistenceService = persistenceService;
        this.transcriptStaleAfterSeconds = transcriptStaleAfterSeconds;
    }

    @Scheduled(fixedDelayString = "${minutes.processing.transcript-dispatch-interval-ms:1000}")
    public void dispatchOnePendingTranscript() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(transcriptStaleAfterSeconds);
        jobRepository.findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PROCESSING,
            cutoff
        ).ifPresent(job -> stateService.recoverStaleProcessing(job.getId(), cutoff));

        jobRepository.findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
            MinutesJobStatus.PROCESSING,
            MinutesProcessingStage.BUILDING_TRANSCRIPT,
            TranscriptPersistenceStatus.PENDING
        ).ifPresent(job -> persistenceService.process(job.getId()));
    }
}
