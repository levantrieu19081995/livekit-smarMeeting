package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingChatMessageType;

public record MeetingChatMessageResponse(
    Long id,
    String clientMessageId,
    String senderDisplayName,
    MeetingChatMessageType messageType,
    String content,
    LocalDateTime sentAt,
    String senderConnectionId
) {
    public MeetingChatMessageResponse(Long id, String clientMessageId, String senderDisplayName,
        MeetingChatMessageType messageType, String content, LocalDateTime sentAt) {
        this(id, clientMessageId, senderDisplayName, messageType, content, sentAt, null);
    }
}
