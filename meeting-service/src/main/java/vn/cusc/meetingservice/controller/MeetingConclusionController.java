package vn.cusc.meetingservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;
import vn.cusc.meetingservice.service.MeetingConclusionService;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/conclusion")
public class MeetingConclusionController {
    private final MeetingConclusionService service;
    @GetMapping public View get(@PathVariable Long meetingId) { return service.get(meetingId); }
    @PostMapping("/generate") @ResponseStatus(HttpStatus.ACCEPTED)
    public View generate(@PathVariable Long meetingId, @Valid @RequestBody Generate request) {
        return service.generate(meetingId, request);
    }
}
