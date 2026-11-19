package vn.cusc.meetingservice.dto.response;

public record MinutesEditorDraftResponse(
    Long jobId,
    Long meetingId,
    int voiceItemCount,
    int chatItemCount,
    int totalItemCount,
    String mainContent,
    String conclusion
) {
}
