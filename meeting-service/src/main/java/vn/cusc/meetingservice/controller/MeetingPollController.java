package vn.cusc.meetingservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.poll.MeetingPollContextResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollRequest;
import vn.cusc.meetingservice.dto.poll.MeetingPollResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollVoteRequest;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.service.MeetingPollService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/polls")
@RequiredArgsConstructor
public class MeetingPollController {
    private final MeetingPollService polls;

    @GetMapping("/context")
    public MeetingPollContextResponse context(@PathVariable Long meetingId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.context(meetingId, token); }

    @GetMapping
    public PageResponse<MeetingPollResponse> list(@PathVariable Long meetingId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) {
        return polls.list(meetingId, page, size, token);
    }

    @GetMapping("/active")
    public ResponseEntity<MeetingPollResponse> active(@PathVariable Long meetingId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) {
        MeetingPollResponse poll = polls.active(meetingId, token);
        return poll == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(poll);
    }

    @GetMapping("/{pollId}")
    public MeetingPollResponse get(@PathVariable Long meetingId, @PathVariable Long pollId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.get(meetingId, pollId, token); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingPollResponse create(@PathVariable Long meetingId, @Valid @RequestBody MeetingPollRequest request, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) {
        return polls.create(meetingId, request, token);
    }

    @PutMapping("/{pollId}")
    public MeetingPollResponse update(@PathVariable Long meetingId, @PathVariable Long pollId,
        @Valid @RequestBody MeetingPollRequest request, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.update(meetingId, pollId, request, token); }

    @PostMapping("/{pollId}/open")
    public MeetingPollResponse open(@PathVariable Long meetingId, @PathVariable Long pollId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.open(meetingId, pollId, token); }

    @PostMapping("/{pollId}/close")
    public MeetingPollResponse close(@PathVariable Long meetingId, @PathVariable Long pollId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.close(meetingId, pollId, token); }

    @PostMapping("/{pollId}/cancel")
    public MeetingPollResponse cancel(@PathVariable Long meetingId, @PathVariable Long pollId, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.cancel(meetingId, pollId, token); }

    @PutMapping("/{pollId}/my-vote")
    public MeetingPollResponse vote(@PathVariable Long meetingId, @PathVariable Long pollId,
        @Valid @RequestBody MeetingPollVoteRequest request, @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) { return polls.vote(meetingId, pollId, request, token); }
}

