package vn.cusc.meetingservice.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import vn.cusc.meetingservice.dto.request.RenderMinutesRequest;
import vn.cusc.meetingservice.dto.response.MinutesRenderResponse;
import vn.cusc.meetingservice.dto.response.MinutesTemplateResponse;
import vn.cusc.meetingservice.service.MeetingMinutesPdfService;
import vn.cusc.meetingservice.service.MeetingMinutesTemplateService;

@RestController
public class MeetingMinutesTemplateController {

    private final MeetingMinutesTemplateService templateService;
    private final MeetingMinutesPdfService pdfService;

    public MeetingMinutesTemplateController(
        MeetingMinutesTemplateService templateService,
        MeetingMinutesPdfService pdfService
    ) {
        this.templateService = templateService;
        this.pdfService = pdfService;
    }

    @GetMapping("/api/minutes/templates")
    public List<MinutesTemplateResponse> listTemplates() {
        return templateService.listTemplates();
    }

    @PostMapping("/api/meetings/{meetingId}/minutes/versions/{version}/render")
    public MinutesRenderResponse render(
        @PathVariable Long meetingId,
        @PathVariable Integer version,
        @Valid @RequestBody RenderMinutesRequest request
    ) {
        return templateService.render(meetingId, version, request);
    }

    @PostMapping("/api/meetings/{meetingId}/minutes/versions/{version}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
        @PathVariable Long meetingId,
        @PathVariable Integer version,
        @Valid @RequestBody RenderMinutesRequest request
    ) {
        byte[] pdf = pdfService.exportPdf(meetingId, version, request);
        String filename = "bien-ban-hop-" + meetingId + "-v" + version + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString()
            )
            .body(pdf);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void handleUnreadableRequest() {
    }
}
