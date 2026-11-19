package vn.cusc.meetingservice.util;

import java.util.Objects;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.security.MeetingPrincipal;

public final class MeetingPermissions {
    private MeetingPermissions() { }

    public static boolean canManage(MeetingPrincipal principal, MeetingEntity meeting,
                                    MeetingParticipantEntity participant) {
        if (principal == null || !MeetingVisibility.canView(principal, meeting, participant)) return false;
        if (meeting.getCreatedByAccount() != null
            && Objects.equals(principal.accountId(), meeting.getCreatedByAccount().getId())) return true;
        return meeting.getAccessType() == MeetingAccessType.PRIVATE
            && participant != null && participant.getStatus() != ParticipantStatus.REMOVED
            && (participant.getRole() == MeetingRole.CHAIRMAN || participant.getRole() == MeetingRole.SECRETARY);
    }
}
