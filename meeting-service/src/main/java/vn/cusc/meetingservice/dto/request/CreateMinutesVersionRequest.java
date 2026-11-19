package vn.cusc.meetingservice.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateMinutesVersionRequest(
    @Positive Integer baseVersion,
    @NotNull JsonNode content
) {
}
