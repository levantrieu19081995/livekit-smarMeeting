package vn.cusc.meetingservice.dto.stt.response;

public record SttJobResultResponse(
    String processingId,
    SttMeetingResultResponse result
) {
}
