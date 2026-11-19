package vn.cusc.meetingservice.service;

import vn.cusc.meetingservice.dto.request.SaveMeetingChatMessageRequest;
import vn.cusc.meetingservice.dto.response.MeetingChatMessageResponse;

public interface MeetingChatMessageService {
    vn.cusc.meetingservice.dto.response.MeetingChatPageResponse listMessages(Long meetingId, String participantToken, long afterId, int size);

    MeetingChatMessageResponse saveMessage(
        Long meetingId,
        String participantToken,
        SaveMeetingChatMessageRequest request
    );
}
