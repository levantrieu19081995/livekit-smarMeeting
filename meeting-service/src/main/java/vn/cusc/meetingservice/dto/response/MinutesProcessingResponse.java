package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;

public record MinutesProcessingResponse(
    Long jobId,
    Long meetingId,
    MinutesJobStatus status,
    MinutesProcessingStage stage,
    Integer progressPercent,
    Integer attempt,
    String message,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
