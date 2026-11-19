package vn.cusc.meetingservice.dto.stt.response;

public record SttTranscriptChunkResponse(
    String sessionId,
    String attendeeId,
    String speakerName,
    String segmentId,
    Integer segmentIndex,
    Integer chunkIndex,
    Long chunkStartMs,
    Long chunkDurationMs,
    String segmentStartedAt,
    String absoluteStartTime,
    String filePath,
    String text
) {
}
