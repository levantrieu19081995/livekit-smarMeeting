package vn.cusc.meetingservice.dto.stt.response;

public record SttFailedChunkResponse(
    String sessionId,
    String segmentId,
    Integer chunkIndex,
    String filePath,
    Integer attempts,
    String error
) {
}
