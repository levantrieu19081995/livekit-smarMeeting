package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;

public record JoinMeetingRequest(
    @NotNull(message = "participantType is required")
    ParticipantJoinType participantType,

    @Size(max = 100, message = "guestId must not exceed 100 characters")
    String guestId,

    @NotBlank(message = "displayName is required")
    @Size(max = 255, message = "displayName must not exceed 255 characters")
    String displayName,
    Long pointId,
    @Size(max = 255, message = "guestResumeToken must not exceed 255 characters") String guestResumeToken
) {
    public JoinMeetingRequest(ParticipantJoinType participantType, String guestId, String displayName, Long pointId) {
        this(participantType, guestId, displayName, pointId, null);
    }
    public JoinMeetingRequest(ParticipantJoinType participantType, String guestId, String displayName) {
        this(participantType, guestId, displayName, null, null);
    }
}
