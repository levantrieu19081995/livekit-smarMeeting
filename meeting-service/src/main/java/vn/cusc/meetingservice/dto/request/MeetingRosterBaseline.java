package vn.cusc.meetingservice.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.cusc.meetingservice.model.enums.MeetingRole;
public record MeetingRosterBaseline(@NotBlank String participantKey, @NotNull MeetingRole role) {}
