package vn.cusc.meetingservice.dto.response;

public record MinutesSummaryResponse(
    long totalMeetings,
    long notGenerated,
    long processing,
    long available
) {
}
