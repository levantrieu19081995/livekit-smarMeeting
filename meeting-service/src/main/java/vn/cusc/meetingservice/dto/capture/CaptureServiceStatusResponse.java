package vn.cusc.meetingservice.dto.capture;

public record CaptureServiceStatusResponse(
    Long captureSessionId,
    Boolean runtimePresent,
    String sessionId,
    String status,
    String message,
    String manifestPath,
    String completedAt
) {
}
