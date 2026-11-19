package vn.cusc.meetingservice.dto.speaking;

import java.time.Instant;
import vn.cusc.meetingservice.model.enums.MeetingSpeakingStatus;

public record MeetingSpeakingRequestResponse(Long id, String requestId, long version, Long pointId,
    String pointName, String speakerName, String requestedByDisplayName, String approvedByDisplayName,
    String finishedByDisplayName, MeetingSpeakingStatus status, Instant requestedAt,
    Instant approvedAt, Instant finishedAt, boolean isOwn, boolean canApprove,
    boolean canFinish, boolean canCancel) { }
