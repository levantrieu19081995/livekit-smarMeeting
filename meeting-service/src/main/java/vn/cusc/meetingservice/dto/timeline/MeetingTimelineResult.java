package vn.cusc.meetingservice.dto.timeline;

import java.util.List;

public record MeetingTimelineResult(
    Long jobId,
    Long meetingId,
    int voiceItemCount,
    int chatItemCount,
    int totalItemCount,
    List<MeetingTimelineItem> items
) {
    public MeetingTimelineResult {
        items = List.copyOf(items);
    }
}
