package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

public record MeetingJoinInfoResponse(
    Long id,
    String title,
    LocalDateTime startTime,
    LocalDateTime endTime,
    MeetingStatus status,
    MeetingAccessType accessType,
    MeetingCreationMode creationMode,
    List<MeetingPointResponse> points
) {
    public MeetingJoinInfoResponse(Long id, String title, LocalDateTime startTime, LocalDateTime endTime,
        MeetingStatus status, MeetingAccessType accessType) {
        this(id, title, startTime, endTime, status, accessType, MeetingCreationMode.SCHEDULED, List.of());
    }
}
