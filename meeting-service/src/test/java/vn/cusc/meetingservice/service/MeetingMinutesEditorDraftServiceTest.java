package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.response.MinutesEditorDraftResponse;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MinutesJobNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingTimelineSourceType;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesEditorDraftServiceTest {

    private static final Long MEETING_ID = 12L;
    private static final Long JOB_ID = 34L;

    @Mock private MeetingMinutesAuthorizationService authorizationService;
    @Mock private MeetingMinutesRepository minutesRepository;
    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesTimelineService timelineService;

    private MeetingMinutesEditorDraftService service;

    @BeforeEach
    void setUp() {
        service = new MeetingMinutesEditorDraftService(
            authorizationService,
            minutesRepository,
            jobRepository,
            timelineService
        );
    }

    @Test
    void draftReadyWithoutExistingVersionReturnsDraft() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item("Speaker A", "account:1", "Noi dung")
        )));

        MinutesEditorDraftResponse response = service.getDraft(MEETING_ID);

        assertEquals(JOB_ID, response.jobId());
        assertEquals(MEETING_ID, response.meetingId());
        assertEquals(1, response.totalItemCount());
        assertEquals("Speaker A: Noi dung", response.mainContent());
    }

    @Test
    void getDraftRequiresMeetingAccess() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of()));

        service.getDraft(MEETING_ID);

        verify(authorizationService).requireMeetingView(MEETING_ID);
    }

    @Test
    void existingMinutesVersionRejectsWithoutBuildingTimeline() {
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(true);

        assertThrows(BusinessValidationException.class, () -> service.getDraft(MEETING_ID));

        verify(timelineService, never()).buildTimeline(any());
    }

    @Test
    void noMinutesJobRejects() {
        stubNoExistingVersion();
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(MEETING_ID))
            .thenReturn(Optional.empty());

        assertThrows(MinutesJobNotFoundException.class, () -> service.getDraft(MEETING_ID));
    }

    @Test
    void voiceAndChatTimelineFormatsInExactOrder() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item("Voice A", "account:1", "Noi dung thu nhat"),
            item("Chat B", "account:2", "Noi dung thu hai")
        )));

        MinutesEditorDraftResponse response = service.getDraft(MEETING_ID);

        assertEquals(
            "Voice A: Noi dung thu nhat\n\nChat B: Noi dung thu hai",
            response.mainContent()
        );
    }

    @Test
    void speakerNameIsUsedWhenPresent() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item("Speaker", "account:1", "content")
        )));

        assertEquals("Speaker: content", service.getDraft(MEETING_ID).mainContent());
    }

    @Test
    void attendeeIdIsUsedWhenSpeakerNameBlank() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item(" ", "account:1", "content")
        )));

        assertEquals("account:1: content", service.getDraft(MEETING_ID).mainContent());
    }

    @Test
    void defaultSpeakerIsUsedWhenSpeakerAndAttendeeBlank() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item(" ", " ", "content")
        )));

        assertEquals("Nguoi tham du: content", service.getDraft(MEETING_ID).mainContent());
    }

    @Test
    void itemsAreSeparatedByTwoNewlines() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item("A", null, "one"),
            item("B", null, "two"),
            item("C", null, "three")
        )));

        assertEquals("A: one\n\nB: two\n\nC: three", service.getDraft(MEETING_ID).mainContent());
    }

    @Test
    void conclusionIsAlwaysEmpty() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item("A", null, "one")
        )));

        assertEquals("", service.getDraft(MEETING_ID).conclusion());
    }

    @Test
    void emptyTimelineReturnsEmptyMainContent() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of()));

        MinutesEditorDraftResponse response = service.getDraft(MEETING_ID);

        assertEquals("", response.mainContent());
        assertEquals("", response.conclusion());
    }

    @Test
    void serviceDoesNotSaveOrWriteRepositories() {
        stubNoExistingVersion();
        stubLatestJob();
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of()));

        service.getDraft(MEETING_ID);

        verify(minutesRepository, never()).save(any(MeetingMinutesEntity.class));
        verify(jobRepository, never()).save(any(MeetingMinutesJobEntity.class));
    }

    private void stubNoExistingVersion() {
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
    }

    private void stubLatestJob() {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(JOB_ID);
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(MEETING_ID))
            .thenReturn(Optional.of(job));
    }

    private MeetingTimelineResult timeline(List<MeetingTimelineItem> items) {
        long voice = items.stream()
            .filter(item -> item.sourceType() == MeetingTimelineSourceType.VOICE)
            .count();
        long chat = items.stream()
            .filter(item -> item.sourceType() == MeetingTimelineSourceType.CHAT)
            .count();
        return new MeetingTimelineResult(
            JOB_ID,
            MEETING_ID,
            (int) voice,
            (int) chat,
            items.size(),
            items
        );
    }

    private MeetingTimelineItem item(
        String speakerName,
        String attendeeId,
        String content
    ) {
        return new MeetingTimelineItem(
            MeetingTimelineSourceType.VOICE,
            Instant.parse("2026-08-26T00:00:00Z"),
            attendeeId,
            speakerName,
            content,
            1L,
            1L
        );
    }
}
