package vn.cusc.meetingservice.mapper;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.config.FrontendProperties;
import vn.cusc.meetingservice.dto.response.MeetingDetailResponse;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.dto.response.MeetingPointResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

@Component
public class MeetingMapper {

    private final FrontendProperties frontendProperties;
    private final MeetingParticipantMapper participantMapper;

    public MeetingMapper(FrontendProperties frontendProperties, MeetingParticipantMapper participantMapper) {
        this.frontendProperties = frontendProperties;
        this.participantMapper = participantMapper;
    }

    public MeetingResponse toResponse(MeetingEntity entity) {
        return toResponse(entity, entity.getStatus());
    }

    public MeetingResponse toResponse(MeetingEntity entity, MeetingStatus responseStatus) {
        return new MeetingResponse(
            entity.getId(),
            entity.getMeetingCode(),
            entity.getTitle(),
            entity.getDescription(),
            entity.getRoomName(),
            entity.getRoomSid(),
            frontendProperties.getBaseUrl() + "/join/" + entity.getId(),
            entity.getStartTime(),
            entity.getEndTime(),
            responseStatus,
            entity.getAccessType(),
            entity.getCreationMode(),
            false
        );
    }

    public MeetingDetailResponse toDetailResponse(MeetingEntity entity) {
        List<MeetingParticipantResponse> participants = entity.getParticipants().stream()
            .map(participantMapper::toResponse)
            .toList();
        return new MeetingDetailResponse(
            entity.getId(),
            entity.getMeetingCode(),
            entity.getTitle(),
            entity.getDescription(),
            entity.getRoomName(),
            entity.getRoomSid(),
            frontendProperties.getBaseUrl() + "/join/" + entity.getId(),
            entity.getStartTime(),
            entity.getEndTime(),
            entity.getStatus(),
            entity.getAccessType(),
            participants,
            entity.getCreationMode(),
            false,
            entity.getPoints().stream().map(point -> new MeetingPointResponse(point.getId(), point.getName())).toList(),
            false
        );
    }
}
