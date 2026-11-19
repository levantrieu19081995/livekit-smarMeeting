package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;

public record MeetingResponse(
    Long id,
    String meetingCode,
    String title,
    String description,
    String roomName,
    String roomSid,
    String meetingUrl,
    LocalDateTime startTime,
    LocalDateTime endTime,
    MeetingStatus status,
    MeetingAccessType accessType,
    MeetingCreationMode creationMode,
    boolean canManage
) {
    public MeetingResponse(Long id, String meetingCode, String title, String description, String roomName,
        String roomSid, String meetingUrl, LocalDateTime startTime, LocalDateTime endTime,
        MeetingStatus status, MeetingAccessType accessType) {
        this(id, meetingCode, title, description, roomName, roomSid, meetingUrl, startTime, endTime,
            status, accessType, MeetingCreationMode.SCHEDULED, false);
    }
    public MeetingResponse withCanManage(boolean value) {
        return new MeetingResponse(id, meetingCode, title, description, roomName, roomSid, meetingUrl,
            startTime, endTime, status, accessType, creationMode, value);
    }
}
