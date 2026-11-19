package vn.cusc.meetingservice.dto.request;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

public record AdminMeetingOverrideRequest(
    LocalDateTime startTime,
    LocalDateTime endTime,
    MeetingStatus status,
    String reason
) {
}
