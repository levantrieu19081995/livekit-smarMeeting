package vn.cusc.meetingservice.dto.response;

import org.springframework.core.io.Resource;

public record MeetingDocumentFileResponse(
    Resource resource,
    String originalFileName,
    String contentType,
    long fileSize,
    boolean inlineSafe
) {
    public MeetingDocumentFileResponse(Resource resource, String originalFileName, String contentType, long fileSize) {
        this(resource, originalFileName, contentType, fileSize, vn.cusc.meetingservice.util.DocumentContentPolicy.inlineType(contentType));
    }
}
