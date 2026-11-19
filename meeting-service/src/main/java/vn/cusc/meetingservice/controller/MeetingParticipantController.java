package vn.cusc.meetingservice.controller;

//Quản lý danh sách người tham gia
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRoleRequest;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;
import vn.cusc.meetingservice.service.MeetingParticipantService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/participants")
@Tag(name = "Participants")
public class MeetingParticipantController {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private vn.cusc.meetingservice.service.MeetingMediaCleanupService mediaCleanup;

    private final MeetingParticipantService participantService;

    public MeetingParticipantController(MeetingParticipantService participantService) {
        this.participantService = participantService;
    }

    @GetMapping
    @Operation(summary = "Get participants")
    public List<MeetingParticipantResponse> getParticipants(@PathVariable Long meetingId) {
        return participantService.getParticipants(meetingId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add participant")
    public MeetingParticipantResponse addParticipant(@PathVariable Long meetingId,
                                                     @Valid @RequestBody AddMeetingParticipantRequest request) {
        return participantService.addParticipant(meetingId, request);
    }

    @PutMapping("/{participantKey}")
    @Operation(summary = "Update participant")
    public MeetingParticipantResponse updateParticipant(@PathVariable Long meetingId, @PathVariable String participantKey,
                                                        @Valid @RequestBody UpdateMeetingParticipantRequest request) {
        return participantService.updateParticipant(meetingId, participantKey, request);
    }

    @PutMapping("/{participantKey}/role")
    @Operation(summary = "Update participant role")
    public MeetingParticipantResponse updateParticipantRole(@PathVariable Long meetingId, @PathVariable String participantKey,
                                                            @Valid @RequestBody UpdateMeetingParticipantRoleRequest request) {
        return participantService.updateParticipantRole(meetingId, participantKey, request);
    }

    @DeleteMapping("/{participantKey}")
    @Operation(summary = "Remove participant")
    public org.springframework.http.ResponseEntity<Void> deleteParticipant(@PathVariable Long meetingId, @PathVariable String participantKey) {
        participantService.deleteParticipant(meetingId, participantKey);
        if (mediaCleanup != null && mediaCleanup.pending(meetingId))
            return org.springframework.http.ResponseEntity.accepted().header("X-Meeting-Cleanup-Pending", "true").build();
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
