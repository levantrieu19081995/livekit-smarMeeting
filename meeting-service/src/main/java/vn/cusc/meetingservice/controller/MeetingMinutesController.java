package vn.cusc.meetingservice.controller;


// Đọc và lưu các phiên bản biên bản
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.request.CreateMinutesVersionRequest;
import vn.cusc.meetingservice.dto.request.SaveMeetingMinutesRequest;
import vn.cusc.meetingservice.dto.request.UpdateMinutesDraftRequest;
import vn.cusc.meetingservice.dto.response.MeetingMinutesResponse;
import vn.cusc.meetingservice.dto.response.MinutesEditorDraftResponse;
import vn.cusc.meetingservice.dto.response.MinutesProcessingResponse;
import vn.cusc.meetingservice.dto.response.MinutesTranscriptEntryResponse;
import vn.cusc.meetingservice.dto.response.MinutesVersionResponse;
import vn.cusc.meetingservice.service.MeetingMinutesEditorDraftService;
import vn.cusc.meetingservice.service.MeetingMinutesProcessingService;
import vn.cusc.meetingservice.service.MeetingMinutesService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/minutes")
@Tag(name = "Minutes")
public class MeetingMinutesController {

    private final MeetingMinutesService meetingMinutesService;
    private final MeetingMinutesProcessingService processingService;
    private final MeetingMinutesEditorDraftService editorDraftService;

    public MeetingMinutesController(
        MeetingMinutesService meetingMinutesService,
        MeetingMinutesProcessingService processingService,
        MeetingMinutesEditorDraftService editorDraftService
    ) {
        this.meetingMinutesService = meetingMinutesService;
        this.processingService = processingService;
        this.editorDraftService = editorDraftService;
    }

    @GetMapping
    @Operation(summary = "Get minutes versions")
    public List<MeetingMinutesResponse> getMinutes(@PathVariable Long meetingId) {
        return meetingMinutesService.getMinutes(meetingId);
    }

    @GetMapping("/transcript")
    public List<MinutesTranscriptEntryResponse> getTranscript(@PathVariable Long meetingId) {
        return meetingMinutesService.getTranscript(meetingId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save minutes")
    public MeetingMinutesResponse saveMinutes(@PathVariable Long meetingId, @Valid @RequestBody SaveMeetingMinutesRequest request) {
        return meetingMinutesService.saveMinutes(meetingId, request);
    }

    @GetMapping("/current")
    public MinutesVersionResponse getCurrent(@PathVariable Long meetingId) {
        return meetingMinutesService.getCurrent(meetingId);
    }

    @GetMapping("/versions")
    public List<MinutesVersionResponse> getVersions(@PathVariable Long meetingId) {
        return meetingMinutesService.getVersions(meetingId);
    }

    @GetMapping("/versions/{version}")
    public MinutesVersionResponse getVersion(@PathVariable Long meetingId, @PathVariable Integer version) {
        return meetingMinutesService.getVersion(meetingId, version);
    }

    @PostMapping("/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public MinutesVersionResponse createVersion(
        @PathVariable Long meetingId,
        @Valid @RequestBody CreateMinutesVersionRequest request
    ) {
        return meetingMinutesService.createVersion(meetingId, request);
    }

    @PutMapping("/versions/{version}")
    public MinutesVersionResponse updateDraft(
        @PathVariable Long meetingId,
        @PathVariable Integer version,
        @Valid @RequestBody UpdateMinutesDraftRequest request
    ) {
        return meetingMinutesService.updateDraft(meetingId, version, request);
    }

    @PostMapping("/versions/{version}/finalize")
    public MinutesVersionResponse finalizeVersion(
        @PathVariable Long meetingId,
        @PathVariable Integer version
    ) {
        return meetingMinutesService.finalizeVersion(meetingId, version);
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MinutesProcessingResponse generate(@PathVariable Long meetingId) {
        return processingService.generate(meetingId);
    }

    @GetMapping("/processing")
    public MinutesProcessingResponse getProcessing(@PathVariable Long meetingId) {
        return processingService.getProcessing(meetingId);
    }

    @GetMapping("/editor-draft")
    public MinutesEditorDraftResponse getEditorDraft(@PathVariable Long meetingId) {
        return editorDraftService.getDraft(meetingId);
    }

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MinutesProcessingResponse retry(@PathVariable Long meetingId) {
        return processingService.retry(meetingId);
    }
}
