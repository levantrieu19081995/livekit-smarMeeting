package vn.cusc.meetingservice.controller;


// Upload, liệt kê và xóa tài liệu
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.dto.response.MeetingDocumentFileResponse;
import vn.cusc.meetingservice.service.MeetingDocumentService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/documents")
@Tag(name = "Documents")
public class MeetingDocumentController {

    private final MeetingDocumentService documentService;

    public MeetingDocumentController(MeetingDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    @Operation(summary = "Get meeting documents")
    public List<MeetingDocumentResponse> getDocuments(@PathVariable Long meetingId) {
        return documentService.getDocuments(meetingId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload meeting document")
    public MeetingDocumentResponse uploadDocument(@PathVariable Long meetingId, @RequestParam("file") MultipartFile file) {
        return documentService.uploadDocument(meetingId, file);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete document")
    public void deleteDocument(@PathVariable Long meetingId, @PathVariable Long documentId) {
        documentService.deleteDocument(meetingId, documentId);
    }

    @GetMapping("/{documentId}/view")
    public ResponseEntity<Resource> viewDocument(
        @PathVariable Long meetingId,
        @PathVariable Long documentId
    ) {
        return fileResponse(documentService.getDocumentFile(meetingId, documentId), false);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> downloadDocument(
        @PathVariable Long meetingId,
        @PathVariable Long documentId
    ) {
        return fileResponse(documentService.getDocumentFile(meetingId, documentId), true);
    }

    private ResponseEntity<Resource> fileResponse(MeetingDocumentFileResponse file, boolean download) {
        ContentDisposition disposition = (download || !file.inlineSafe()
            ? ContentDisposition.attachment()
            : ContentDisposition.inline())
            .filename(file.originalFileName(), StandardCharsets.UTF_8)
            .build();
        MediaType contentType;
        try {
            contentType = file.inlineSafe() ? MediaType.parseMediaType(file.contentType()) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IllegalArgumentException exception) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'self'")
            .header("Cache-Control", "private, no-store")
            .contentType(contentType)
            .contentLength(file.fileSize())
            .body(file.resource());
    }
}
