package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

public record UpdateMeetingRequest(
    @NotBlank(message = "title is required") String title,
    String description,
    LocalDateTime startTime,
    LocalDateTime endTime,
    @NotNull(message = "accessType is required") MeetingAccessType accessType,
    MeetingStatus status,
    List<String> pointNames,
    @Valid List<AddMeetingParticipantRequest> participants,
    @Valid List<MeetingRosterBaseline> rosterBaseline
) {
    public UpdateMeetingRequest(String title, String description, LocalDateTime startTime, LocalDateTime endTime,
        MeetingAccessType accessType, MeetingStatus status, List<String> pointNames, List<AddMeetingParticipantRequest> participants) {
        this(title, description, startTime, endTime, accessType, status, pointNames, participants, null);
    }
    public UpdateMeetingRequest(String title, String description, LocalDateTime startTime, LocalDateTime endTime,
        MeetingAccessType accessType, MeetingStatus status, List<String> pointNames) {
        this(title, description, startTime, endTime, accessType, status, pointNames, null, null);
    }
    public UpdateMeetingRequest(String title, String description, LocalDateTime startTime, LocalDateTime endTime,
        MeetingAccessType accessType, MeetingStatus status) {
        this(title, description, startTime, endTime, accessType, status, null, null, null);
    }
}
