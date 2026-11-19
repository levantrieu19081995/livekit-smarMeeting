package vn.cusc.meetingservice.dto.qa;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.cusc.meetingservice.model.enums.MeetingQaAction;

public record MeetingQaActionRequest(@NotNull MeetingQaAction action, @NotNull @Min(0) Long expectedVersion,
    @Size(max = 5000) String answer) {
    public MeetingQaActionRequest(MeetingQaAction action, Long expectedVersion) {
        this(action, expectedVersion, null);
    }
}
