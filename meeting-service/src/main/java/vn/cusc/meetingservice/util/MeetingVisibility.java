package vn.cusc.meetingservice.util;

import org.springframework.data.jpa.domain.Specification;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.security.MeetingPrincipal;

/** The same public/private visibility rule for paginated lists and direct reads. */
public final class MeetingVisibility {
    private MeetingVisibility() { }

    public static boolean canView(MeetingPrincipal principal, MeetingEntity meeting,
                                  MeetingParticipantEntity participant) {
        if (principal == null || !principal.isEnabled()) return false;
        if (meeting.getAccessType() == MeetingAccessType.PUBLIC) return true;
        if (meeting.getCreatedByAccount() != null
            && principal.accountId().equals(meeting.getCreatedByAccount().getId())) return true;
        return member(principal, participant)
            || meeting.getParticipants().stream().anyMatch(item -> member(principal, item));
    }

    private static boolean member(MeetingPrincipal principal, MeetingParticipantEntity participant) {
        if (participant == null || participant.getStatus() == ParticipantStatus.REMOVED) return false;
        if (participant.getUserAccount() != null) {
            return principal.accountId().equals(participant.getUserAccount().getId());
        }
        return principal.normalizedEmail() != null && !principal.normalizedEmail().isBlank()
            && principal.normalizedEmail().equals(participant.getInvitedEmailNormalized());
    }

    public static Specification<MeetingEntity> visibleTo(MeetingPrincipal principal) {
        return (root, query, builder) -> {
            var membership = query.subquery(Long.class);
            var participant = membership.from(MeetingParticipantEntity.class);
            membership.select(participant.get("id")).where(
                builder.equal(participant.get("meeting"), root),
                builder.or(
                    builder.equal(participant.get("userAccount").get("id"), principal.accountId()),
                    builder.and(
                        builder.isNull(participant.get("userAccount")),
                        builder.equal(participant.get("invitedEmailNormalized"), principal.normalizedEmail())
                    )
                ),
                builder.notEqual(participant.get("status"), ParticipantStatus.REMOVED)
            );
            return builder.or(
                builder.equal(root.get("accessType"), MeetingAccessType.PUBLIC),
                builder.equal(root.get("createdByAccount").get("id"), principal.accountId()),
                builder.exists(membership)
            );
        };
    }
}
