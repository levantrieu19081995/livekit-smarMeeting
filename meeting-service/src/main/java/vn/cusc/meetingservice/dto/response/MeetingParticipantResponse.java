package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;

public record MeetingParticipantResponse(
    Long id,
    String participantKey,
    String userId,
    String userPublicId,
    String invitedEmail,
    String fullName,
    String email,
    MeetingRole role,
    ParticipantStatus status,
    LocalDateTime joinedAt,
    LocalDateTime leftAt
) {
}
