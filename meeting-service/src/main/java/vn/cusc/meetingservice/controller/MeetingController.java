package vn.cusc.meetingservice.controller;

// CRUD meeting, join và end meeting
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingRequest;
import vn.cusc.meetingservice.dto.response.JoinMeetingResponse;
import vn.cusc.meetingservice.dto.response.MeetingDetailResponse;
import vn.cusc.meetingservice.dto.response.MeetingJoinInfoResponse;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.service.MeetingService;

@RestController
@RequestMapping("/api/meetings")
@Tag(name = "Meetings")
public class MeetingController {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private vn.cusc.meetingservice.service.MeetingMediaCleanupService mediaCleanup;

    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create meeting")
    public MeetingResponse createMeeting(@Valid @RequestBody CreateMeetingRequest request) {
        return meetingService.createMeeting(request);
    }

    @GetMapping
    @Operation(summary = "List meetings")
    public PageResponse<MeetingResponse> getMeetings(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) MeetingStatus status,
        @RequestParam(required = false) MeetingAccessType accessType,
        @RequestParam(required = false) String fromDate,
        @RequestParam(required = false) String toDate,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "startTime,desc") String sort
    ) {
        Pageable pageable = vn.cusc.meetingservice.util.ApiPageRequests.meeting(page, size, sort);
        vn.cusc.meetingservice.util.ApiPageRequests.query(search);
        return meetingService.getMeetings(search, status, accessType, fromDate, toDate, pageable);
    }

    @GetMapping("/{meetingId}")
    @Operation(summary = "Get meeting detail")
    public MeetingDetailResponse getMeeting(@PathVariable Long meetingId) {
        return meetingService.getMeeting(meetingId);
    }

    @GetMapping("/{meetingId}/join-info")
    @Operation(summary = "Get public meeting join info")
    public MeetingJoinInfoResponse getJoinInfo(@PathVariable Long meetingId) {
        return meetingService.getJoinInfo(meetingId);
    }

    @PutMapping("/{meetingId}")
    @Operation(summary = "Update meeting")
    public MeetingResponse updateMeeting(@PathVariable Long meetingId, @Valid @RequestBody UpdateMeetingRequest request) {
        return meetingService.updateMeeting(meetingId, request);
    }

    @DeleteMapping("/{meetingId}")
    @Operation(summary = "Delete meeting")
    public org.springframework.http.ResponseEntity<Void> deleteMeeting(@PathVariable Long meetingId) {
        meetingService.deleteMeeting(meetingId);
        return cleanupResponse(meetingId);
    }

    @PostMapping("/{meetingId}/join")
    @Operation(summary = "Join meeting and receive LiveKit token")
    public JoinMeetingResponse joinMeeting(@PathVariable Long meetingId, @Valid @RequestBody JoinMeetingRequest request) {
        return meetingService.joinMeeting(meetingId, request);
    }

    @PostMapping("/{meetingId}/end")
    @Operation(summary = "End meeting")
    public org.springframework.http.ResponseEntity<Void> endMeeting(@PathVariable Long meetingId) {
        meetingService.endMeeting(meetingId);
        return cleanupResponse(meetingId);
    }
    private org.springframework.http.ResponseEntity<Void> cleanupResponse(Long meetingId) {
        if (mediaCleanup != null && mediaCleanup.pending(meetingId))
            return org.springframework.http.ResponseEntity.accepted().header("X-Meeting-Cleanup-Pending", "true").build();
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
