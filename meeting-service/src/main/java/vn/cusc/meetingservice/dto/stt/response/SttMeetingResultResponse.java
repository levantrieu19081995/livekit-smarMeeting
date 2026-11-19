package vn.cusc.meetingservice.dto.stt.response;

import java.util.List;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;

public record SttMeetingResultResponse(
    Long meetingId,
    List<String> sessionIds,
    SttTerminalStatus status,
    Integer totalManifests,
    Integer totalSegments,
    Integer totalChunks,
    Integer processedChunks,
    Integer succeededChunks,
    Integer failedChunks,
    List<SttTranscriptChunkResponse> transcripts,
    List<SttFailedChunkResponse> failures
) {
}
