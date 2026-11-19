package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.NotNull;
import vn.cusc.meetingservice.model.enums.MeetingRole;

public record UpdateMeetingParticipantRoleRequest(
    @NotNull(message = "role is required") MeetingRole role
) {
}
