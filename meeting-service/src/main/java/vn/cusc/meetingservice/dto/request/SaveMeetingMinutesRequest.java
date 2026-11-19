package vn.cusc.meetingservice.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record SaveMeetingMinutesRequest(
    String rawTranscript,
    @NotNull(message = "minutes is required") JsonNode minutes
) {
}
