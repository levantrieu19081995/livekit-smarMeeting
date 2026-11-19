package vn.cusc.meetingservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.qa.MeetingQaContextResponse;
import vn.cusc.meetingservice.dto.qa.MeetingQaActionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaQuestionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaQuestionResponse;
import vn.cusc.meetingservice.dto.qa.MeetingQaSettingsRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaUpvoteRequest;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.MeetingQaFilter;
import vn.cusc.meetingservice.model.enums.MeetingQaSort;
import vn.cusc.meetingservice.service.MeetingQaService;

@RestController
@RequestMapping("/api/meetings/{meetingId}/qa")
@RequiredArgsConstructor
public class MeetingQaController {
    private final MeetingQaService qa;

    @GetMapping("/context")
    public MeetingQaContextResponse context(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) {
        return qa.context(meetingId, token);
    }

    @GetMapping("/questions")
    public PageResponse<MeetingQaQuestionResponse> list(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "NEWEST") MeetingQaSort sort,
        @RequestParam(defaultValue = "ALL") MeetingQaFilter filter) {
        return qa.list(meetingId, token, page, size, sort, filter);
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingQaQuestionResponse ask(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @Valid @RequestBody MeetingQaQuestionRequest request) {
        return qa.ask(meetingId, token, request);
    }

    @PutMapping("/questions/{questionId}/upvote")
    public MeetingQaQuestionResponse upvote(@PathVariable Long meetingId, @PathVariable Long questionId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @Valid @RequestBody MeetingQaUpvoteRequest request) {
        return qa.upvote(meetingId, questionId, token, request);
    }

    @PutMapping("/settings")
    public MeetingQaContextResponse settings(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @Valid @RequestBody MeetingQaSettingsRequest request) {
        return qa.updateSettings(meetingId, token, request);
    }

    @PostMapping("/questions/{questionId}/actions")
    public MeetingQaQuestionResponse moderate(@PathVariable Long meetingId, @PathVariable Long questionId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token,
        @Valid @RequestBody MeetingQaActionRequest request) {
        return qa.moderate(meetingId, questionId, token, request);
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@PathVariable Long meetingId,
        @RequestHeader(value = "X-Meeting-Participant-Token", required = false) String token) {
        byte[] csv = qa.report(meetingId, token).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore())
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename("hoi-dap-cuoc-hop-" + meetingId + ".csv").build().toString()).body(csv);
    }
}
