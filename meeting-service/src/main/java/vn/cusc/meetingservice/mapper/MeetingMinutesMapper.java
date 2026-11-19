package vn.cusc.meetingservice.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.dto.response.AccountSummaryResponse;
import vn.cusc.meetingservice.dto.response.MeetingMinutesResponse;
import vn.cusc.meetingservice.dto.response.MinutesVersionResponse;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;

@Component
public class MeetingMinutesMapper {

    private final ObjectMapper objectMapper;

    public MeetingMinutesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MeetingMinutesResponse toResponse(MeetingMinutesEntity entity) {
        try {
            JsonNode minutes = objectMapper.readTree(entity.getMinutesJson());
            return new MeetingMinutesResponse(
                entity.getId(),
                entity.getVersion(),
                entity.getRawTranscript(),
                minutes,
                entity.getGeneratedAt()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Minutes JSON is invalid", exception);
        }
    }

    public MinutesVersionResponse toVersionResponse(MeetingMinutesEntity entity) {
        try {
            var account = entity.getCreatedByAccount();
            return new MinutesVersionResponse(
                entity.getId(),
                entity.getMeeting().getId(),
                entity.getVersion(),
                entity.getStatus(),
                entity.getSource(),
                objectMapper.readTree(entity.getMinutesJson()),
                account == null ? null : new AccountSummaryResponse(account.getPublicId(), account.getFullName()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getFinalizedAt(),
                entity.getRevision()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Minutes JSON is invalid", exception);
        }
    }
}
