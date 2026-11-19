package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveMeetingChatMessageRequest(

    @NotBlank(message = "clientMessageId is required")
    @Size(
        max = 36,
        message = "clientMessageId must not exceed 36 characters"
    )
    String clientMessageId,

    @NotBlank(message = "content is required")
    @Size(
        max = 10000,
        message = "content must not exceed 10000 characters"
    )
    String content

) {
}
