package vn.cusc.meetingservice.exception;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(Long documentId) {
        super("Khong tim thay tai lieu voi id=" + documentId);
    }
}
