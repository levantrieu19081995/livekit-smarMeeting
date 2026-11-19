package vn.cusc.meetingservice.service;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.dto.response.MeetingDocumentFileResponse;

public interface MeetingDocumentService {
    List<MeetingDocumentResponse> getDocuments(Long meetingId);
    MeetingDocumentResponse uploadDocument(Long meetingId, MultipartFile file);
    void deleteDocument(Long meetingId, Long documentId);
    MeetingDocumentFileResponse getDocumentFile(Long meetingId, Long documentId);
}
