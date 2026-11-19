package vn.cusc.meetingservice.dto.timeline;

import java.time.Instant;
import vn.cusc.meetingservice.model.enums.MeetingTimelineSourceType;

public record MeetingTimelineItem(
    MeetingTimelineSourceType sourceType,
    Instant occurredAt,
    String attendeeId,
    String speakerName,
    String content,
    Long sourceId,
    Long sourceOrder
) {
}
