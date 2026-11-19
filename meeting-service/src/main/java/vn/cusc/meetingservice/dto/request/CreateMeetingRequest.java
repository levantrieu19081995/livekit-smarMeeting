package vn.cusc.meetingservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;

public record CreateMeetingRequest(
    String title,
    String description,
    LocalDateTime startTime,
    LocalDateTime endTime,
    @NotNull(message = "accessType is required") MeetingAccessType accessType,
    @Valid List<AddMeetingParticipantRequest> participants,
    MeetingCreationMode creationMode,
    List<String> pointNames,
    String clientRequestId
) {
    public CreateMeetingRequest(String title, String description, LocalDateTime startTime,
        LocalDateTime endTime, MeetingAccessType accessType, List<AddMeetingParticipantRequest> participants,
        MeetingCreationMode creationMode, List<String> pointNames) {
        this(title, description, startTime, endTime, accessType, participants, creationMode, pointNames, null);
    }
    public CreateMeetingRequest(String title, String description, LocalDateTime startTime,
        LocalDateTime endTime, MeetingAccessType accessType, List<AddMeetingParticipantRequest> participants,
        MeetingCreationMode creationMode) {
        this(title, description, startTime, endTime, accessType, participants, creationMode, null, null);
    }
    public CreateMeetingRequest(String title, String description, LocalDateTime startTime,
        LocalDateTime endTime, MeetingAccessType accessType, List<AddMeetingParticipantRequest> participants) {
        this(title, description, startTime, endTime, accessType, participants, MeetingCreationMode.SCHEDULED, null, null);
    }
}
