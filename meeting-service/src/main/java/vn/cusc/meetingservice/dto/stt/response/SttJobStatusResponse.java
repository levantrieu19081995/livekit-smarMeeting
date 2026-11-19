package vn.cusc.meetingservice.dto.stt.response;

public record SttJobStatusResponse(
    String processingId,
    Long meetingId,
    String status,
    String stage,
    Integer processedChunks,
    Integer totalChunks,
    Double progressPercent,
    String error
) {
}
