package vn.cusc.meetingservice.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record MeetingMinutesResponse(
    Long id,
    Integer version,
    String rawTranscript,
    JsonNode minutes,
    LocalDateTime generatedAt
) {
}
