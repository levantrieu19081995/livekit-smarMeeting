package vn.cusc.meetingservice.dto.response;
import java.util.List;
public record MeetingChatPageResponse(List<MeetingChatMessageResponse> content, boolean hasMore, Long nextAfterId) {}
