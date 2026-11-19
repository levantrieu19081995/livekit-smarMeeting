package vn.cusc.meetingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.config.MeetingTimelineProperties;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingChatMessageEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingTranscriptChunkEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MeetingTimelineSourceType;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingChatMessageRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingTranscriptChunkRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesTimelineServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long MEETING_ID = 20L;

    @Mock
    private MeetingMinutesJobRepository jobRepository;

    @Mock
    private MeetingTranscriptChunkRepository chunkRepository;

    @Mock
    private MeetingChatMessageRepository chatRepository;

    private MeetingMinutesTimelineService service;

    @BeforeEach
    void setUp() {
        MeetingTimelineProperties properties = new MeetingTimelineProperties();
        properties.setZoneId(ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new MeetingMinutesTimelineService(
            jobRepository,
            chunkRepository,
            chatRepository,
            properties
        );
    }

    @Test
    void buildTimelineAllowsReadyCompletedJob() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "2026-08-26T00:27:57Z", "hello")));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.totalItemCount()).isEqualTo(1);
    }

    @Test
    void buildTimelineAllowsReadyPartialJob() {
        stubValidJob(SttTerminalStatus.PARTIAL);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.totalItemCount()).isZero();
    }

    @Test
    void buildTimelineRejectsTranscriptThatIsNotReady() {
        stubJob(job(TranscriptPersistenceStatus.PENDING, MinutesProcessingStage.GENERATING_MINUTES));

        assertThatThrownBy(() -> service.buildTimeline(JOB_ID))
            .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void buildTimelineRejectsBuildingTranscriptStage() {
        stubJob(job(TranscriptPersistenceStatus.READY, MinutesProcessingStage.BUILDING_TRANSCRIPT));

        assertThatThrownBy(() -> service.buildTimeline(JOB_ID))
            .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void buildTimelineRejectsTranscribingStage() {
        stubJob(job(TranscriptPersistenceStatus.READY, MinutesProcessingStage.TRANSCRIBING));

        assertThatThrownBy(() -> service.buildTimeline(JOB_ID))
            .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void buildTimelineSupportsVoiceOnly() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(
                voice(1L, 1, "2026-08-26T00:27:57Z", "hello"),
                voice(2L, 2, "2026-08-26T00:28:57Z", "world")
            ));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.voiceItemCount()).isEqualTo(2);
        assertThat(result.chatItemCount()).isZero();
        assertThat(result.totalItemCount()).isEqualTo(2);
    }

    @Test
    void buildTimelineSupportsChatOnly() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(
                chat(1L, "A", LocalDateTime.of(2026, 8, 26, 7, 28, 10)),
                chat(2L, "B", LocalDateTime.of(2026, 8, 26, 7, 29, 10))
            ));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.voiceItemCount()).isZero();
        assertThat(result.chatItemCount()).isEqualTo(2);
        assertThat(result.totalItemCount()).isEqualTo(2);
    }

    @Test
    void buildTimelineNormalizesVoiceAndChatTimezone() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "2026-08-26T00:27:57Z", "voice")));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(chat(2L, "chat", LocalDateTime.of(2026, 8, 26, 7, 28, 10))));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items()).extracting(MeetingTimelineItem::occurredAt)
            .containsExactly(
                Instant.parse("2026-08-26T00:27:57Z"),
                Instant.parse("2026-08-26T00:28:10Z")
            );
        assertThat(result.items()).extracting(MeetingTimelineItem::sourceType)
            .containsExactly(MeetingTimelineSourceType.VOICE, MeetingTimelineSourceType.CHAT);
    }

    @Test
    void buildTimelineParsesVoiceOffsetTimestamp() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "2026-08-26T07:27:57+07:00", "voice")));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items().get(0).occurredAt())
            .isEqualTo(Instant.parse("2026-08-26T00:27:57Z"));
    }

    @Test
    void buildTimelineFallsBackToSegmentTimestampPlusChunkStart() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        MeetingTranscriptChunkEntity chunk = voice(1L, 1, null, "voice");
        chunk.setSegmentStartedAt("2026-08-26T00:27:57Z");
        chunk.setChunkStartMs(5000L);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(chunk));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items().get(0).occurredAt())
            .isEqualTo(Instant.parse("2026-08-26T00:28:02Z"));
    }

    @Test
    void buildTimelineRejectsInvalidVoiceTimestamp() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "invalid", "voice")));

        assertThatThrownBy(() -> service.buildTimeline(JOB_ID))
            .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void buildTimelineRejectsMissingVoiceClock() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        MeetingTranscriptChunkEntity chunk = voice(1L, 1, null, "voice");
        chunk.setSegmentStartedAt(null);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(chunk));

        assertThatThrownBy(() -> service.buildTimeline(JOB_ID))
            .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void buildTimelineExcludesBlankVoiceContent() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(
                voice(1L, 1, "2026-08-26T00:27:57Z", "one"),
                voice(2L, 2, "2026-08-26T00:28:57Z", ""),
                voice(3L, 3, "2026-08-26T00:29:57Z", "three")
            ));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.voiceItemCount()).isEqualTo(2);
        assertThat(result.items()).extracting(MeetingTimelineItem::content)
            .containsExactly("one", "three");
    }

    @Test
    void buildTimelineUsesChatAttendeeIdFromConnection() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        MeetingChatMessageEntity message =
            chat(1L, "content", LocalDateTime.of(2026, 8, 26, 7, 28, 10));
        MeetingParticipantConnectionEntity connection = new MeetingParticipantConnectionEntity();
        connection.setAttendeeId("account:123");
        message.setParticipantConnection(connection);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(message));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items().get(0).attendeeId()).isEqualTo("account:123");
    }

    @Test
    void buildTimelineAllowsChatWithNullConnection() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        MeetingChatMessageEntity message =
            chat(1L, "content", LocalDateTime.of(2026, 8, 26, 7, 28, 10));
        message.setParticipantConnection(null);
        message.setSenderDisplayName("Guest A");
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(message));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items().get(0).attendeeId()).isNull();
        assertThat(result.items().get(0).speakerName()).isEqualTo("Guest A");
    }

    @Test
    void buildTimelineKeepsSameAttendeeForVoiceAndChatWithoutMerging() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        MeetingTranscriptChunkEntity chunk =
            voice(1L, 1, "2026-08-26T00:27:57Z", "voice");
        chunk.setAttendeeId("account:123");
        MeetingChatMessageEntity message =
            chat(2L, "chat", LocalDateTime.of(2026, 8, 26, 7, 28, 10));
        MeetingParticipantConnectionEntity connection = new MeetingParticipantConnectionEntity();
        connection.setAttendeeId("account:123");
        message.setParticipantConnection(connection);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(chunk));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(message));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(MeetingTimelineItem::attendeeId)
            .containsExactly("account:123", "account:123");
    }

    @Test
    void buildTimelineSortsVoiceBeforeChatAtSameTimestamp() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "2026-08-26T10:00:00Z", "voice")));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(chat(2L, "chat", LocalDateTime.of(2026, 8, 26, 17, 0, 0))));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items()).extracting(MeetingTimelineItem::sourceType)
            .containsExactly(MeetingTimelineSourceType.VOICE, MeetingTimelineSourceType.CHAT);
    }

    @Test
    void buildTimelineSortsSameTimestampBySourceOrder() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(
                voice(2L, 2, "2026-08-26T10:00:00Z", "two"),
                voice(1L, 1, "2026-08-26T10:00:00Z", "one")
            ));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items()).extracting(MeetingTimelineItem::sourceOrder)
            .containsExactly(1L, 2L);
    }

    @Test
    void buildTimelineKeepsOverlappingVoiceItems() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(
                voice(1L, 1, "2026-08-26T10:00:00Z", "speaker one"),
                voice(2L, 2, "2026-08-26T10:00:01Z", "speaker two")
            ));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(MeetingTimelineItem::content)
            .containsExactly("speaker one", "speaker two");
    }

    @Test
    void buildTimelineExcludesBlankChatContent() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of(chat(1L, "   ", LocalDateTime.of(2026, 8, 26, 7, 28, 10))));

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThat(result.chatItemCount()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void buildTimelineReturnsImmutableItems() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of(voice(1L, 1, "2026-08-26T00:27:57Z", "voice")));
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        MeetingTimelineResult result = service.buildTimeline(JOB_ID);

        assertThatThrownBy(() -> result.items().add(result.items().get(0)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void buildTimelineDoesNotCallRepositoryWrites() {
        stubValidJob(SttTerminalStatus.COMPLETED);
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID))
            .thenReturn(List.of());
        when(chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID))
            .thenReturn(List.of());

        service.buildTimeline(JOB_ID);

        verify(jobRepository).findById(JOB_ID);
        verify(chunkRepository).findByMinutesJob_IdOrderByResultOrderAsc(JOB_ID);
        verify(chatRepository).findByMeetingIdOrderBySentAtAscIdAsc(MEETING_ID);
        verifyNoMoreInteractions(jobRepository, chunkRepository, chatRepository);
    }

    private void stubValidJob(SttTerminalStatus sttStatus) {
        MeetingMinutesJobEntity job = job(
            TranscriptPersistenceStatus.READY,
            MinutesProcessingStage.GENERATING_MINUTES
        );
        job.setSttStatus(sttStatus);
        when(job.getMeeting().getId()).thenReturn(MEETING_ID);
        stubJob(job);
    }

    private void stubJob(MeetingMinutesJobEntity job) {
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    private MeetingMinutesJobEntity job(
        TranscriptPersistenceStatus transcriptStatus,
        MinutesProcessingStage stage
    ) {
        MeetingEntity meeting = mock(MeetingEntity.class);

        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(JOB_ID);
        job.setMeeting(meeting);
        job.setStatus(MinutesJobStatus.PROCESSING);
        job.setStage(stage);
        job.setTranscriptStatus(transcriptStatus);
        job.setSttStatus(SttTerminalStatus.COMPLETED);
        job.setSttProcessingId("stt-123");
        return job;
    }

    private MeetingTranscriptChunkEntity voice(
        Long id,
        Integer resultOrder,
        String absoluteStartTime,
        String text
    ) {
        MeetingTranscriptChunkEntity chunk = new MeetingTranscriptChunkEntity();
        chunk.setId(id);
        chunk.setResultOrder(resultOrder);
        chunk.setAbsoluteStartTime(absoluteStartTime);
        chunk.setSegmentStartedAt(null);
        chunk.setChunkStartMs(0L);
        chunk.setAttendeeId("account:voice");
        chunk.setSpeakerName("Voice Speaker");
        chunk.setText(text);
        return chunk;
    }

    private MeetingChatMessageEntity chat(
        Long id,
        String content,
        LocalDateTime sentAt
    ) {
        MeetingChatMessageEntity message = new MeetingChatMessageEntity();
        message.setId(id);
        message.setContent(content);
        message.setSentAt(sentAt);
        message.setSenderDisplayName("Chat Speaker");
        return message;
    }
}
