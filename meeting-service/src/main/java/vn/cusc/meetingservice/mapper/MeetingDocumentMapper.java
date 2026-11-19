package vn.cusc.meetingservice.mapper;

import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.entity.MeetingDocumentEntity;

@Component
public class MeetingDocumentMapper {

    public MeetingDocumentResponse toResponse(MeetingDocumentEntity entity) {
        var uploader = entity.getUploadedByAccount();
        return new MeetingDocumentResponse(
            entity.getId(),
            entity.getOriginalFileName(),
            entity.getStoredFileName(),
            entity.getContentType(),
            entity.getFileSize(),
            entity.getStoragePath(),
            entity.getCreatedAt(),
            entity.getMeeting().getId(),
            uploader == null
                ? new vn.cusc.meetingservice.dto.response.AccountSummaryResponse(null, "Khong xac dinh")
                : new vn.cusc.meetingservice.dto.response.AccountSummaryResponse(
                    uploader.getPublicId(),
                    uploader.getFullName()
                ),
            entity.getSource()
        );
    }
}
