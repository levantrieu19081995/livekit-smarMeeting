package vn.cusc.meetingservice.dto.qa;

import vn.cusc.meetingservice.model.enums.MeetingStatus;

public record MeetingQaContextResponse(boolean enabled, boolean anonymousAllowed, boolean moderationEnabled,
    long settingsVersion, MeetingStatus meetingStatus, boolean canManage, boolean canAsk, boolean canUpvote) { }
