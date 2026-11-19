package vn.cusc.meetingservice.dto.response;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingDocumentSource;

public record MeetingDocumentResponse(
    Long id,
    String originalFileName,
    String storedFileName,
    String contentType,
    Long fileSize,
    String storagePath,
    LocalDateTime createdAt,
    Long meetingId,
    AccountSummaryResponse uploadedBy,
    MeetingDocumentSource source
) {
}
