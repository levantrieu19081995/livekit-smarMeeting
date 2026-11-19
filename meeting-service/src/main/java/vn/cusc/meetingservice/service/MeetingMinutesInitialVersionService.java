package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;

@Service
public class MeetingMinutesInitialVersionService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final MeetingMinutesTimelineService timelineService;
    private final ObjectMapper objectMapper;

    public MeetingMinutesInitialVersionService(
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesRepository minutesRepository,
        MeetingMinutesTimelineService timelineService,
        ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.minutesRepository = minutesRepository;
        this.timelineService = timelineService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void createInitialVersion(Long jobId) {
        MeetingMinutesJobEntity job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new BusinessValidationException("Minutes job not found"));
        validateJob(job);

        Long meetingId = job.getMeeting().getId();
        if (minutesRepository.existsByMeetingId(meetingId)) {
            completeJob(job);
            return;
        }

        MeetingTimelineResult timeline = timelineService.buildTimeline(job.getId());
        MeetingMinutesEntity version = new MeetingMinutesEntity();
        version.setMeeting(job.getMeeting());
        version.setVersion(1);
        version.setStatus(MinutesStatus.DRAFT);
        version.setSource(MinutesVersionSource.SYSTEM_GENERATED);
        version.setMinutesJson(serializeContent(formatContent(timeline)));
        version.setGeneratedAt(LocalDateTime.now());
        version.setCreatedByAccount(null);

        minutesRepository.save(version);
        completeJob(job);
    }

    @Transactional
    public void markGenerationFailure(Long jobId, String errorMessage) {
        jobRepository.findByIdForUpdate(jobId).ifPresent(job -> {
            if (job.getStatus() != MinutesJobStatus.PROCESSING
                || job.getStage() != MinutesProcessingStage.GENERATING_MINUTES
                || job.getTranscriptStatus() != TranscriptPersistenceStatus.READY) {
                return;
            }

            job.setStatus(MinutesJobStatus.FAILED);
            job.setStage(MinutesProcessingStage.FAILED);
            job.setErrorMessage(safeErrorMessage(errorMessage));
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private void validateJob(MeetingMinutesJobEntity job) {
        if (
            job.getStatus() != MinutesJobStatus.PROCESSING ||
            job.getStage() != MinutesProcessingStage.GENERATING_MINUTES ||
            job.getTranscriptStatus() != TranscriptPersistenceStatus.READY
        ) {
            throw new BusinessValidationException("Minutes job is not ready for initial version");
        }
    }

    private ObjectNode formatContent(MeetingTimelineResult timeline) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("mainContent", formatMainContent(timeline));
        content.put("conclusion", "");
        return content;
    }

    private String formatMainContent(MeetingTimelineResult timeline) {
        return timeline.items().stream()
            .filter(item -> item.content() != null && !item.content().isBlank())
            .map(this::formatItem)
            .collect(Collectors.joining("\n\n"));
    }

    private String formatItem(MeetingTimelineItem item) {
        return speaker(item) + ": " + item.content().trim();
    }

    private String speaker(MeetingTimelineItem item) {
        if (item.speakerName() != null && !item.speakerName().isBlank()) {
            return item.speakerName();
        }
        if (item.attendeeId() != null && !item.attendeeId().isBlank()) {
            return item.attendeeId();
        }
        return "Nguoi tham du";
    }

    private String serializeContent(ObjectNode content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new BusinessValidationException("Noi dung bien ban ban dau khong hop le");
        }
    }

    private void completeJob(MeetingMinutesJobEntity job) {
        job.setStatus(MinutesJobStatus.COMPLETED);
        job.setStage(MinutesProcessingStage.DRAFT_READY);
        job.setProgressPercent(100);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorMessage(null);
        jobRepository.save(job);
    }

    private String safeErrorMessage(String errorMessage) {
        String message = errorMessage == null || errorMessage.isBlank()
            ? "Initial minutes version generation failed"
            : errorMessage;
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
