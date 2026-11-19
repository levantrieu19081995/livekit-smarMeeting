package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.cusc.meetingservice.model.enums.MeetingRole;

public record AddMeetingParticipantRequest(
    String userPublicId,
    String invitedEmail,
    String fullName,
    @NotNull(message = "role is required") MeetingRole role,
    String participantKey
) {
    public AddMeetingParticipantRequest(String userPublicId, String invitedEmail, String fullName, MeetingRole role) {
        this(userPublicId, invitedEmail, fullName, role, null);
    }
}
