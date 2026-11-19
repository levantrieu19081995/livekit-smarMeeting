package vn.cusc.meetingservice.service;

import org.springframework.data.domain.Pageable;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingRequest;
import vn.cusc.meetingservice.dto.response.JoinMeetingResponse;
import vn.cusc.meetingservice.dto.response.MeetingDetailResponse;
import vn.cusc.meetingservice.dto.response.MeetingJoinInfoResponse;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;

public interface MeetingService {
    MeetingResponse createMeeting(CreateMeetingRequest request);
    PageResponse<MeetingResponse> getMeetings(String search, MeetingStatus status, MeetingAccessType accessType,
                                             String fromDate, String toDate, Pageable pageable);
    MeetingDetailResponse getMeeting(Long meetingId);
    MeetingJoinInfoResponse getJoinInfo(Long meetingId);
    MeetingResponse updateMeeting(Long meetingId, UpdateMeetingRequest request);
    void deleteMeeting(Long meetingId);
    JoinMeetingResponse joinMeeting(Long meetingId, JoinMeetingRequest request);
    void endMeeting(Long meetingId);
}
