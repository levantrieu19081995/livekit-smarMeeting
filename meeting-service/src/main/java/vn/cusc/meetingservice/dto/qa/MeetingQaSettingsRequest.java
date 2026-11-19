package vn.cusc.meetingservice.dto.qa;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MeetingQaSettingsRequest(@NotNull Boolean enabled, @NotNull Boolean anonymousAllowed,
    @NotNull Boolean moderationEnabled, @NotNull @Min(0) Long expectedVersion) { }
