package vn.cusc.meetingservice.dto.poll;

import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

public record MeetingPollContextResponse(
    boolean canManage, MeetingStatus meetingStatus, List<Candidate> candidates
) {
    public record Candidate(String participantKey, String fullName, MeetingRole role) {}
}
