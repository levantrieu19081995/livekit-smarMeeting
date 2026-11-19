package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;

public record MeetingDetailResponse(
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
    List<MeetingParticipantResponse> participants,
    MeetingCreationMode creationMode,
    boolean canManage,
    List<MeetingPointResponse> points,
    boolean pointsEditable
) {
    public MeetingDetailResponse(Long id, String meetingCode, String title, String description, String roomName,
        String roomSid, String meetingUrl, LocalDateTime startTime, LocalDateTime endTime, MeetingStatus status,
        MeetingAccessType accessType, List<MeetingParticipantResponse> participants, MeetingCreationMode creationMode,
        boolean canManage) {
        this(id, meetingCode, title, description, roomName, roomSid, meetingUrl, startTime, endTime,
            status, accessType, participants, creationMode, canManage, List.of(), false);
    }
    public MeetingDetailResponse(Long id, String meetingCode, String title, String description, String roomName,
        String roomSid, String meetingUrl, LocalDateTime startTime, LocalDateTime endTime, MeetingStatus status,
        MeetingAccessType accessType, List<MeetingParticipantResponse> participants) {
        this(id, meetingCode, title, description, roomName, roomSid, meetingUrl, startTime, endTime,
            status, accessType, participants, MeetingCreationMode.SCHEDULED, false);
    }
    public MeetingDetailResponse withCanManage(boolean value) {
        return new MeetingDetailResponse(id, meetingCode, title, description, roomName, roomSid, meetingUrl,
            startTime, endTime, status, accessType, participants, creationMode, value, points, pointsEditable);
    }
    public MeetingDetailResponse withPointsEditable(boolean value) {
        return new MeetingDetailResponse(id, meetingCode, title, description, roomName, roomSid, meetingUrl,
            startTime, endTime, status, accessType, participants, creationMode, canManage, points, value);
    }
}
