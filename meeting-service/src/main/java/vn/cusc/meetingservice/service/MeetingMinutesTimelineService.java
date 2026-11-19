package vn.cusc.meetingservice.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.config.MeetingTimelineProperties;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.entity.MeetingChatMessageEntity;
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

@Service
public class MeetingMinutesTimelineService {

    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingTranscriptChunkRepository chunkRepository;
    private final MeetingChatMessageRepository chatRepository;
    private final MeetingTimelineProperties timelineProperties;

    public MeetingMinutesTimelineService(
        MeetingMinutesJobRepository jobRepository,
        MeetingTranscriptChunkRepository chunkRepository,
        MeetingChatMessageRepository chatRepository,
        MeetingTimelineProperties timelineProperties
    ) {
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.chatRepository = chatRepository;
        this.timelineProperties = timelineProperties;
    }

    @Transactional(readOnly = true)
    public MeetingTimelineResult buildTimeline(Long jobId) {
        MeetingMinutesJobEntity job = jobRepository.findById(jobId)
            .orElseThrow(() -> new BusinessValidationException("Minutes job khong ton tai"));
        validateJobReadyForTimeline(job);

        Long meetingId = job.getMeeting().getId();
        List<MeetingTimelineItem> items = new ArrayList<>();

        List<MeetingTranscriptChunkEntity> chunks =
            chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(jobId);
        for (MeetingTranscriptChunkEntity chunk : chunks) {
            toVoiceItem(chunk).ifPresent(items::add);
        }

        List<MeetingChatMessageEntity> messages =
            chatRepository.findByMeetingIdOrderBySentAtAscIdAsc(meetingId);
        for (MeetingChatMessageEntity message : messages) {
            toChatItem(message).ifPresent(items::add);
        }

        items.sort(
            Comparator.comparing(MeetingTimelineItem::occurredAt)
                .thenComparingInt(item -> sourcePriority(item.sourceType()))
                .thenComparing(MeetingTimelineItem::sourceOrder)
                .thenComparing(MeetingTimelineItem::sourceId)
        );

        int voiceItemCount = countItems(items, MeetingTimelineSourceType.VOICE);
        int chatItemCount = countItems(items, MeetingTimelineSourceType.CHAT);
        int totalItemCount = voiceItemCount + chatItemCount;

        if (items.size() != totalItemCount) {
            throw new BusinessValidationException("Timeline count khong hop le");
        }

        return new MeetingTimelineResult(
            job.getId(),
            meetingId,
            voiceItemCount,
            chatItemCount,
            totalItemCount,
            items
        );
    }

    private void validateJobReadyForTimeline(MeetingMinutesJobEntity job) {
        if (job.getStatus() != MinutesJobStatus.PROCESSING) {
            throw new BusinessValidationException("Minutes job khong o trang thai PROCESSING");
        }
        if (job.getStage() != MinutesProcessingStage.GENERATING_MINUTES) {
            throw new BusinessValidationException("Minutes job khong o stage GENERATING_MINUTES");
        }
        if (job.getTranscriptStatus() != TranscriptPersistenceStatus.READY) {
            throw new BusinessValidationException("Transcript chua READY");
        }
        if (
            job.getSttStatus() != SttTerminalStatus.COMPLETED &&
            job.getSttStatus() != SttTerminalStatus.PARTIAL
        ) {
            throw new BusinessValidationException("STT status khong hop le de build timeline");
        }
        if (job.getSttProcessingId() == null || job.getSttProcessingId().isBlank()) {
            throw new BusinessValidationException("STT processing id khong hop le");
        }
    }

    private java.util.Optional<MeetingTimelineItem> toVoiceItem(
        MeetingTranscriptChunkEntity chunk
    ) {
        String text = chunk.getText();
        if (text != null && text.isBlank()) {
            return java.util.Optional.empty();
        }
        if (text == null) {
            throw new BusinessValidationException("Voice transcript text bi null");
        }

        Instant occurredAt = normalizeVoiceTimestamp(chunk);
        return java.util.Optional.of(new MeetingTimelineItem(
            MeetingTimelineSourceType.VOICE,
            occurredAt,
            chunk.getAttendeeId(),
            chunk.getSpeakerName(),
            text,
            chunk.getId(),
            chunk.getResultOrder().longValue()
        ));
    }

    private java.util.Optional<MeetingTimelineItem> toChatItem(
        MeetingChatMessageEntity message
    ) {
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return java.util.Optional.empty();
        }

        MeetingParticipantConnectionEntity connection =
            message.getParticipantConnection();
        String attendeeId = connection != null ? connection.getAttendeeId() : null;

        return java.util.Optional.of(new MeetingTimelineItem(
            MeetingTimelineSourceType.CHAT,
            normalizeChatTimestamp(message.getSentAt()),
            attendeeId,
            message.getSenderDisplayName(),
            content,
            message.getId(),
            message.getId()
        ));
    }

    private Instant normalizeVoiceTimestamp(MeetingTranscriptChunkEntity chunk) {
        if (chunk.getAbsoluteStartTime() != null && !chunk.getAbsoluteStartTime().isBlank()) {
            return parseOffsetTimestamp(
                chunk.getAbsoluteStartTime(),
                "absoluteStartTime"
            );
        }
        if (chunk.getSegmentStartedAt() == null || chunk.getSegmentStartedAt().isBlank()) {
            throw new BusinessValidationException("Voice timeline clock bi thieu");
        }
        Long chunkStartMs = chunk.getChunkStartMs();
        if (chunkStartMs == null) {
            throw new BusinessValidationException("Voice chunkStartMs bi null");
        }
        return parseOffsetTimestamp(
            chunk.getSegmentStartedAt(),
            "segmentStartedAt"
        ).plusMillis(chunkStartMs);
    }

    private Instant parseOffsetTimestamp(String value, String fieldName) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw new BusinessValidationException(fieldName + " khong phai timestamp offset/Z hop le");
        }
    }

    private Instant normalizeChatTimestamp(LocalDateTime sentAt) {
        if (sentAt == null) {
            throw new BusinessValidationException("Chat sentAt bi null");
        }
        try {
            return sentAt.atZone(timelineProperties.getZoneId()).toInstant();
        } catch (DateTimeException exception) {
            throw new BusinessValidationException("Chat sentAt khong the normalize");
        }
    }

    private int countItems(
        List<MeetingTimelineItem> items,
        MeetingTimelineSourceType sourceType
    ) {
        return (int) items.stream()
            .filter(item -> item.sourceType() == sourceType)
            .count();
    }

    private int sourcePriority(MeetingTimelineSourceType sourceType) {
        return switch (sourceType) {
            case VOICE -> 0;
            case CHAT -> 1;
        };
    }
}
