package vn.cusc.meetingservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.dto.speaking.*;
import vn.cusc.meetingservice.service.MeetingSpeakingService;

@RestController @RequiredArgsConstructor @RequestMapping("/api/meetings/{meetingId}/speaking")
public class MeetingSpeakingController {
    private final MeetingSpeakingService speaking;
    @GetMapping("/context")
    public MeetingSpeakingContextResponse context(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return speaking.context(meetingId, token); }
    @GetMapping("/requests")
    public PageResponse<MeetingSpeakingRequestResponse> list(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return speaking.list(meetingId, token, page, size); }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED)
    public MeetingSpeakingRequestResponse request(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestBody CreateSpeakingRequest request) { return speaking.request(meetingId, token, request); }
    @PatchMapping("/requests/{id}/approve")
    public MeetingSpeakingRequestResponse approve(@PathVariable Long meetingId, @PathVariable Long id,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestBody SpeakingActionRequest request) { return speaking.approve(meetingId, id, token, request); }
    @PatchMapping("/requests/{id}/finish")
    public MeetingSpeakingRequestResponse finish(@PathVariable Long meetingId, @PathVariable Long id,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestBody SpeakingActionRequest request) { return speaking.finish(meetingId, id, token, request); }
    @PatchMapping("/requests/{id}/cancel")
    public MeetingSpeakingRequestResponse cancel(@PathVariable Long meetingId, @PathVariable Long id,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestBody SpeakingActionRequest request) { return speaking.cancel(meetingId, id, token, request); }
}
