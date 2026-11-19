package vn.cusc.meetingservice.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record UpdateMinutesDraftRequest(@NotNull JsonNode content, @NotNull Long expectedRevision) {
    public UpdateMinutesDraftRequest(JsonNode content) { this(content, 0L); }
}
