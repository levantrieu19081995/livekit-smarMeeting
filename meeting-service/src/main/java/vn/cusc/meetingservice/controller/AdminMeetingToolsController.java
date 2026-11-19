package vn.cusc.meetingservice.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.request.AdminMeetingOverrideRequest;
import vn.cusc.meetingservice.service.AdminMeetingToolsService;

@RestController
@RequestMapping("/api/admin/meeting-tools")
public class AdminMeetingToolsController {

    private final AdminMeetingToolsService adminMeetingToolsService;

    public AdminMeetingToolsController(AdminMeetingToolsService adminMeetingToolsService) {
        this.adminMeetingToolsService = adminMeetingToolsService;
    }

    @PatchMapping("/meetings/{meetingId}/override")
    public org.springframework.http.ResponseEntity<Void> overrideMeeting(
        @PathVariable Long meetingId,
        @Valid @RequestBody AdminMeetingOverrideRequest request
    ) {
        adminMeetingToolsService.overrideMeeting(meetingId, request);
        if (adminMeetingToolsService.cleanupPending(meetingId))
            return org.springframework.http.ResponseEntity.accepted().header("X-Meeting-Cleanup-Pending", "true").build();
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/meetings/{meetingId}/reset-minutes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetMinutes(@PathVariable Long meetingId) {
        adminMeetingToolsService.resetMinutesArtifacts(meetingId);
    }
}
