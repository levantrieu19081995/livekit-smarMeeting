package vn.cusc.meetingservice.util;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;

@Component
public class MeetingStatusResolver {

    public MeetingStatus resolve(MeetingEntity meeting) {
        return resolve(meeting, LocalDateTime.now());
    }

    public MeetingStatus resolve(MeetingEntity meeting, LocalDateTime now) {
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            return MeetingStatus.CANCELLED;
        }
        if (meeting.getStatus() == MeetingStatus.COMPLETED) {
            return MeetingStatus.COMPLETED;
        }
        // These meetings start through an explicit action, not a scheduled clock deadline.
        // A wall-clock correction must not move an already-started room back to UPCOMING.
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS && meeting.getStartTime() != null
            && meeting.getEndTime() == null && (meeting.getCreationMode() == MeetingCreationMode.INSTANT
                || meeting.getCreationMode() == MeetingCreationMode.LATER)) {
            return MeetingStatus.IN_PROGRESS;
        }
        if (meeting.getStartTime() == null || now.isBefore(meeting.getStartTime())) {
            return MeetingStatus.UPCOMING;
        }
        if (meeting.getEndTime() == null || !now.isAfter(meeting.getEndTime())) {
            return MeetingStatus.IN_PROGRESS;
        }
        return MeetingStatus.COMPLETED;
    }
}
