package vn.cusc.meetingservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.response.MeetingCaptureResponse;
import vn.cusc.meetingservice.service.MeetingCaptureService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/capture")
@Tag(name = "Capture")
public class MeetingCaptureController {

    private final MeetingCaptureService captureService;

    public MeetingCaptureController(MeetingCaptureService captureService) {
        this.captureService = captureService;
    }

    @GetMapping("/status")
    @Operation(summary = "Get manual audio capture status")
    public MeetingCaptureResponse status(@PathVariable Long meetingId) {
        return captureService.getStatus(meetingId);
    }

    @GetMapping("/sessions")
    @Operation(summary = "List capture sessions in chronological order")
    public List<MeetingCaptureResponse> sessions(@PathVariable Long meetingId) {
        return captureService.getSessions(meetingId);
    }

    @PostMapping("/start")
    @Operation(summary = "Start manual audio capture")
    public MeetingCaptureResponse start(@PathVariable Long meetingId) {
        return captureService.start(meetingId);
    }

    @PostMapping("/stop")
    @Operation(summary = "Stop manual audio capture and finalize manifest")
    public MeetingCaptureResponse stop(@PathVariable Long meetingId) {
        return captureService.stop(meetingId);
    }
}
