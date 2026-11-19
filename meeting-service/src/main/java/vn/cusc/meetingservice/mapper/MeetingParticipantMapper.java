package vn.cusc.meetingservice.mapper;

import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;

@Component
public class MeetingParticipantMapper {

    public MeetingParticipantResponse toResponse(MeetingParticipantEntity entity) {
        return new MeetingParticipantResponse(
            entity.getId(),
            entity.getParticipantKey(),
            entity.getUserId(),
            entity.getUserAccount() != null ? entity.getUserAccount().getPublicId() : null,
            entity.getInvitedEmailNormalized(),
            entity.getFullName(),
            entity.getEmail(),
            entity.getRole(),
            entity.getStatus(),
            entity.getJoinedAt(),
            entity.getLeftAt()
        );
    }
}
