package vn.cusc.meetingservice.dto.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeetingQaQuestionRequest(
    @NotBlank @Size(max = 36) String clientQuestionId,
    @NotBlank(message = "Câu hỏi không được để trống.") @Size(max = 2000) String content,
    boolean anonymous) { }
