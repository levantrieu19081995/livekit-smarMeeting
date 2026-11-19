package vn.cusc.meetingservice.service;

import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.response.MinutesEditorDraftResponse;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MinutesJobNotFoundException;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;

@Service
public class MeetingMinutesEditorDraftService {

    private final MeetingMinutesAuthorizationService authorizationService;
    private final MeetingMinutesRepository minutesRepository;
    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesTimelineService timelineService;

    public MeetingMinutesEditorDraftService(
        MeetingMinutesAuthorizationService authorizationService,
        MeetingMinutesRepository minutesRepository,
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesTimelineService timelineService
    ) {
        this.authorizationService = authorizationService;
        this.minutesRepository = minutesRepository;
        this.jobRepository = jobRepository;
        this.timelineService = timelineService;
    }

    @Transactional(readOnly = true)
    public MinutesEditorDraftResponse getDraft(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);

        if (minutesRepository.existsByMeetingId(meetingId)) {
            throw new BusinessValidationException("Cuoc hop da co bien ban, hay dung minutes current");
        }

        MeetingMinutesJobEntity job =
            jobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId)
                .orElseThrow(() -> new MinutesJobNotFoundException(meetingId));

        MeetingTimelineResult timeline = timelineService.buildTimeline(job.getId());

        return new MinutesEditorDraftResponse(
            timeline.jobId(),
            timeline.meetingId(),
            timeline.voiceItemCount(),
            timeline.chatItemCount(),
            timeline.totalItemCount(),
            formatMainContent(timeline),
            ""
        );
    }

    private String formatMainContent(MeetingTimelineResult timeline) {
        return timeline.items().stream()
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
}
