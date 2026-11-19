package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.CaptureStatus;

public record MeetingCaptureResponse(
    Long id,
    String externalSessionId,
    CaptureStatus status,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String manifestPath,
    String errorMessage
) {
}
