package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MeetingTimelineSourceType;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesInitialVersionServiceTest {

    private static final Long JOB_ID = 15L;
    private static final Long MEETING_ID = 12L;

    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesRepository minutesRepository;
    @Mock private MeetingMinutesTimelineService timelineService;

    private ObjectMapper objectMapper;
    private MeetingMinutesInitialVersionService service;
    private MeetingMinutesJobEntity job;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MeetingMinutesInitialVersionService(
            jobRepository,
            minutesRepository,
            timelineService,
            objectMapper
        );
        job = draftJob();
    }

    @Test
    void createsSystemGeneratedInitialV1FromTimeline() throws Exception {
        stubReadyJob();
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item(MeetingTimelineSourceType.VOICE, "levantrieu", "account:1", "Noi dung voice"),
            item(MeetingTimelineSourceType.CHAT, "lvtrieu4", "account:2", "Noi dung chat")
        )));
        when(minutesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createInitialVersion(JOB_ID);

        MeetingMinutesEntity saved = capturedMinutes();
        assertEquals(1, saved.getVersion());
        assertEquals(MinutesVersionSource.SYSTEM_GENERATED, saved.getSource());
        assertEquals(MinutesStatus.DRAFT, saved.getStatus());
        assertEquals(null, saved.getCreatedByAccount());
        JsonNode content = objectMapper.readTree(saved.getMinutesJson());
        assertEquals(
            "levantrieu: Noi dung voice\n\nlvtrieu4: Noi dung chat",
            content.get("mainContent").asText()
        );
        assertEquals("", content.get("conclusion").asText());
    }

    @Test
    void blankTimelineItemDoesNotBreakV1() throws Exception {
        stubReadyJob();
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of(
            item(MeetingTimelineSourceType.VOICE, "Speaker", "account:1", " "),
            item(MeetingTimelineSourceType.CHAT, "Chat", "account:2", "content")
        )));
        when(minutesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createInitialVersion(JOB_ID);

        JsonNode content = objectMapper.readTree(capturedMinutes().getMinutesJson());
        assertEquals("Chat: content", content.get("mainContent").asText());
    }

    @Test
    void saveSuccessCompletesJobAsDraftReadyAt100() {
        stubReadyJob();
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of()));
        when(minutesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createInitialVersion(JOB_ID);

        assertEquals(MinutesJobStatus.COMPLETED, job.getStatus());
        assertEquals(MinutesProcessingStage.DRAFT_READY, job.getStage());
        assertEquals(100, job.getProgressPercent());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void saveFailureDoesNotMarkDraftReady() {
        stubReadyJob();
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
        when(timelineService.buildTimeline(JOB_ID)).thenReturn(timeline(List.of()));
        when(minutesRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.createInitialVersion(JOB_ID));

        assertEquals(MinutesJobStatus.PROCESSING, job.getStatus());
        assertEquals(MinutesProcessingStage.GENERATING_MINUTES, job.getStage());
        assertEquals(80, job.getProgressPercent());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markGenerationFailureFailsEligibleJob() {
        job.setSttProcessingId("python-123");
        job.setTranscriptAttempts(2);
        job.setTranscriptPersistedAt(LocalDateTime.now().minusMinutes(1));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, "generation failed");

        assertEquals(MinutesJobStatus.FAILED, job.getStatus());
        assertEquals(MinutesProcessingStage.FAILED, job.getStage());
        assertEquals("generation failed", job.getErrorMessage());
        assertNotNull(job.getFinishedAt());
        assertEquals(TranscriptPersistenceStatus.READY, job.getTranscriptStatus());
        assertEquals(2, job.getTranscriptAttempts());
        assertNotNull(job.getTranscriptPersistedAt());
        assertEquals("python-123", job.getSttProcessingId());
        verify(jobRepository).save(job);
    }

    @Test
    void markGenerationFailureUsesDeterministicMessageWhenBlank() {
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, " ");

        assertEquals("Initial minutes version generation failed", job.getErrorMessage());
    }

    @Test
    void markGenerationFailureBoundsLongMessage() {
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, "x".repeat(1200));

        assertEquals(1000, job.getErrorMessage().length());
    }

    @Test
    void markGenerationFailureCompletedDraftReadyJobIsNoOp() {
        job.setStatus(MinutesJobStatus.COMPLETED);
        job.setStage(MinutesProcessingStage.DRAFT_READY);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, "generation failed");

        assertEquals(MinutesJobStatus.COMPLETED, job.getStatus());
        assertEquals(MinutesProcessingStage.DRAFT_READY, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markGenerationFailureWrongStageIsNoOp() {
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, "generation failed");

        assertEquals(MinutesProcessingStage.BUILDING_TRANSCRIPT, job.getStage());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markGenerationFailureWrongTranscriptStatusIsNoOp() {
        job.setTranscriptStatus(TranscriptPersistenceStatus.PROCESSING);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        service.markGenerationFailure(JOB_ID, "generation failed");

        assertEquals(TranscriptPersistenceStatus.PROCESSING, job.getTranscriptStatus());
        verify(jobRepository, never()).save(job);
    }

    @Test
    void markGenerationFailureMissingJobIsNoOp() {
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.empty());

        service.markGenerationFailure(JOB_ID, "generation failed");

        verify(jobRepository, never()).save(any());
    }

    @Test
    void existingV1DoesNotCreateDuplicateAndCompletesJob() {
        stubReadyJob();
        when(minutesRepository.existsByMeetingId(MEETING_ID)).thenReturn(true);

        service.createInitialVersion(JOB_ID);

        verify(minutesRepository, never()).save(any());
        verify(timelineService, never()).buildTimeline(any());
        assertEquals(MinutesJobStatus.COMPLETED, job.getStatus());
        assertEquals(MinutesProcessingStage.DRAFT_READY, job.getStage());
        assertEquals(100, job.getProgressPercent());
    }

    @Test
    void rejectsJobThatIsNotGeneratingMinutes() {
        job.setStage(MinutesProcessingStage.DRAFT_READY);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThrows(BusinessValidationException.class, () -> service.createInitialVersion(JOB_ID));
    }

    private void stubReadyJob() {
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
    }

    private MeetingMinutesJobEntity draftJob() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(MEETING_ID);

        MeetingMinutesJobEntity entity = new MeetingMinutesJobEntity();
        entity.setId(JOB_ID);
        entity.setMeeting(meeting);
        entity.setStatus(MinutesJobStatus.PROCESSING);
        entity.setStage(MinutesProcessingStage.GENERATING_MINUTES);
        entity.setTranscriptStatus(TranscriptPersistenceStatus.READY);
        entity.setProgressPercent(80);
        return entity;
    }

    private MeetingTimelineResult timeline(List<MeetingTimelineItem> items) {
        int voiceCount = (int) items.stream()
            .filter(item -> item.sourceType() == MeetingTimelineSourceType.VOICE)
            .count();
        int chatCount = (int) items.stream()
            .filter(item -> item.sourceType() == MeetingTimelineSourceType.CHAT)
            .count();
        return new MeetingTimelineResult(
            JOB_ID,
            MEETING_ID,
            voiceCount,
            chatCount,
            items.size(),
            items
        );
    }

    private MeetingTimelineItem item(
        MeetingTimelineSourceType sourceType,
        String speakerName,
        String attendeeId,
        String content
    ) {
        return new MeetingTimelineItem(
            sourceType,
            Instant.parse("2026-08-26T00:00:00Z"),
            attendeeId,
            speakerName,
            content,
            1L,
            1L
        );
    }

    private MeetingMinutesEntity capturedMinutes() {
        ArgumentCaptor<MeetingMinutesEntity> captor =
            ArgumentCaptor.forClass(MeetingMinutesEntity.class);
        verify(minutesRepository).save(captor.capture());
        return captor.getValue();
    }
}
