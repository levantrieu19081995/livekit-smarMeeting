package vn.cusc.meetingservice.service;

import java.util.List;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRoleRequest;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;

public interface MeetingParticipantService {
    List<MeetingParticipantResponse> getParticipants(Long meetingId);
    MeetingParticipantResponse addParticipant(Long meetingId, AddMeetingParticipantRequest request);
    MeetingParticipantResponse updateParticipant(Long meetingId, String participantKey, UpdateMeetingParticipantRequest request);
    MeetingParticipantResponse updateParticipantRole(Long meetingId, String participantKey, UpdateMeetingParticipantRoleRequest request);
    void deleteParticipant(Long meetingId, String participantKey);
}
