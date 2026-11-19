package vn.cusc.meetingservice.dto.capture;

public record StopCaptureRequest(
    Long meetingId,
    String externalSessionId
) {
}
