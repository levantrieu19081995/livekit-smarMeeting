package vn.cusc.meetingservice.dto.response;

public record MinutesTranscriptEntryResponse(
    Long id,
    String attendeeId,
    String speakerName,
    String absoluteStartTime,
    String segmentStartedAt,
    Long chunkStartMs,
    String text
) {
}
