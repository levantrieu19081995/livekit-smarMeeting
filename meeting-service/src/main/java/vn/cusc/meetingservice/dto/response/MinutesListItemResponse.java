package vn.cusc.meetingservice.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MinutesStatus;

public record MinutesListItemResponse(
    Long meetingId,
    String meetingCode,
    String title,
    LocalDate date,
    LocalTime startTime,
    LocalTime endTime,
    AccountSummaryResponse chairman,
    int participantCount,
    List<MinutesParticipantPreviewResponse> participantPreview,
    MinutesStatus minutesStatus,
    Integer currentVersion,
    LocalDateTime updatedAt,
    MeetingRole myRole,
    boolean canManage
) {
}
