package vn.cusc.meetingservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.request.SaveMeetingChatMessageRequest;
import vn.cusc.meetingservice.dto.response.MeetingChatMessageResponse;
import vn.cusc.meetingservice.service.MeetingChatMessageService;

@RestController
@RequestMapping(
    "/api/meetings/{meetingId}/chat/messages"
)
@Tag(name = "Meeting Chat")
public class MeetingChatMessageController {
    @org.springframework.web.bind.annotation.GetMapping
    public vn.cusc.meetingservice.dto.response.MeetingChatPageResponse list(
        @PathVariable Long meetingId,
        @RequestHeader(value = PARTICIPANT_TOKEN_HEADER, required = false) String token,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long afterId,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int size) {
        return chatMessageService.listMessages(meetingId, token, afterId, size);
    }

    private static final String PARTICIPANT_TOKEN_HEADER =
        "X-Meeting-Participant-Token";

    private final MeetingChatMessageService chatMessageService;

    public MeetingChatMessageController(
        MeetingChatMessageService chatMessageService
    ) {
        this.chatMessageService = chatMessageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Persist meeting text chat message")
    public MeetingChatMessageResponse saveMessage(
        @PathVariable Long meetingId,

        @RequestHeader(
            value = PARTICIPANT_TOKEN_HEADER,
            required = false
        )
        String participantToken,

        @Valid
        @RequestBody
        SaveMeetingChatMessageRequest request
    ) {
        return chatMessageService.saveMessage(
            meetingId,
            participantToken,
            request
        );
    }
}
