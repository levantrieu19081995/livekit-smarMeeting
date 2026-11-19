package vn.cusc.meetingservice.dto.qa;

import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MeetingQaStatus;

public record MeetingQaQuestionResponse(Long id, String clientQuestionId, String content, boolean anonymous,
    String authorDisplayName, MeetingQaStatus status, LocalDateTime createdAt, LocalDateTime updatedAt,
    LocalDateTime answeredAt, long upvoteCount, boolean upvotedByMe, boolean mine, long version,
    String answer, String answerAuthorDisplayName) { }
