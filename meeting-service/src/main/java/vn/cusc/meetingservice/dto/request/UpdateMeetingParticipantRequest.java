package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.cusc.meetingservice.model.enums.MeetingRole;

public record UpdateMeetingParticipantRequest(
    @NotBlank(message = "fullName is required") String fullName,
    @NotBlank(message = "email is required") @Email(message = "email is invalid") String email,
    @NotNull(message = "role is required") MeetingRole role
) {
}
