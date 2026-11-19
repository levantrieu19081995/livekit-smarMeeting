package vn.cusc.meetingservice.service;

import java.util.List;
import vn.cusc.meetingservice.dto.response.MeetingCaptureResponse;

public interface MeetingCaptureService {
    MeetingCaptureResponse getStatus(Long meetingId);
    List<MeetingCaptureResponse> getSessions(Long meetingId);
    MeetingCaptureResponse start(Long meetingId);
    MeetingCaptureResponse stop(Long meetingId);
    void finalizeForTerminalTransition(Long meetingId);
}
