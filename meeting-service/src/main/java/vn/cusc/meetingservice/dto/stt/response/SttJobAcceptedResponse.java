package vn.cusc.meetingservice.dto.stt.response;

public record SttJobAcceptedResponse(
    String processingId,
    Long meetingId,
    String status
) {
}
