package vn.cusc.meetingservice.dto.speaking;

import java.util.List;
import vn.cusc.meetingservice.dto.response.MeetingPointResponse;

public record MeetingSpeakingContextResponse(boolean pointMeeting, boolean canModerate, boolean readOnly,
    boolean canRequest, Long pointId, String pointName, String displayName, List<MeetingPointResponse> points) { }
