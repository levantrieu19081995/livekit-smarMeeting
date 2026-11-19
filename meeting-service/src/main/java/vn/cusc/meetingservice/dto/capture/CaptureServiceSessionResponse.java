package vn.cusc.meetingservice.dto.capture;

public record CaptureServiceSessionResponse(
    String sessionId,
    String status,
    String message,
    String manifestPath,
    String completedAt
) {
}
