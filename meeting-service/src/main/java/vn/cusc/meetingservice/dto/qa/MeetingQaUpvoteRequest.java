package vn.cusc.meetingservice.dto.qa;

import jakarta.validation.constraints.NotNull;

public record MeetingQaUpvoteRequest(@NotNull Boolean upvoted) { }
