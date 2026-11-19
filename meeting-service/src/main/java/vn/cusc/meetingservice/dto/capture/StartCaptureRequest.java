package vn.cusc.meetingservice.dto.capture;

public record StartCaptureRequest(
    Long meetingId,
    Long captureSessionId,
    String roomName,
    String livekitUrl,
    String agentIdentity
) {
}
