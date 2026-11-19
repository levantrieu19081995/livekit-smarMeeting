package vn.cusc.meetingservice.security;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingQaException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;
import vn.cusc.meetingservice.util.MeetingVisibility;

/** Identity and meeting scope shared by the open Q&A and poll workflows. */
public final class MeetingParticipantAccess {
    private MeetingParticipantAccess() { }

    public record Actor(MeetingPrincipal principal, MeetingParticipantEntity participant, boolean hasParticipantToken) {
        public boolean canManage(MeetingEntity meeting) {
            return hasParticipantToken || active(participant)
                || (principal != null && meeting.getCreatedByAccount() != null
                    && Objects.equals(principal.accountId(), meeting.getCreatedByAccount().getId()));
        }
    }

    public static Actor viewer(MeetingEntity meeting, String token, String scope, CurrentUserService current,
        MeetingParticipantRepository participants, MeetingParticipantConnectionRepository connections,
        UserAccountRepository accounts) {
        MeetingPrincipal principal = current.getCurrentPrincipal().orElse(null);
        if (principal != null && !AccountCredentialVersions.matches(principal, accounts.findById(principal.accountId()).orElse(null))) denied(scope, "ACCOUNT_DISABLED");
        MeetingParticipantEntity participant = principal == null ? null
            : participants.findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId()).orElse(null);
        if (token != null) {
            if (token.isBlank() || token.length() > 255) denied(scope, "INVALID_PARTICIPANT_TOKEN");
            var connection = connections.findByMeetingIdAndChatTokenHash(meeting.getId(), MeetingChatTokenUtils.sha256(token))
                .orElseThrow(() -> failure(scope, "INVALID_PARTICIPANT_TOKEN"));
            if (connection.getStatus() != ParticipantConnectionStatus.PENDING
                && connection.getStatus() != ParticipantConnectionStatus.CONNECTED) denied(scope, "CONNECTION_NOT_CONNECTED");
            participant = connection.getParticipant();
            if (!active(participant) || !Objects.equals(meeting.getId(), participant.getMeeting().getId())) denied(scope, "PARTICIPANT_REMOVED");
            if (participant.getUserAccount() != null
                && !AccountCredentialVersions.matches(connection, accounts.findById(participant.getUserAccount().getId()).orElse(null))) denied(scope, "ACCOUNT_DISABLED");
            if (principal != null && (participant.getUserAccount() == null
                || !Objects.equals(principal.accountId(), participant.getUserAccount().getId()))) denied(scope, "PARTICIPANT_IDENTITY_MISMATCH");
            if (meeting.getAccessType() != MeetingAccessType.PUBLIC
                && !MeetingVisibility.canView(principal, meeting, participant)) denied(scope, "ACCESS_FORBIDDEN");
            return new Actor(principal, participant, true);
        }
        if (!MeetingVisibility.canView(principal, meeting, participant)) denied(scope, "ACCESS_FORBIDDEN");
        return new Actor(principal, active(participant) ? participant : null, false);
    }

    public static boolean active(MeetingParticipantEntity participant) {
        return participant != null && participant.getStatus() != ParticipantStatus.REMOVED;
    }
    private static void denied(String scope, String code) { throw failure(scope, code); }
    private static RuntimeException failure(String scope, String code) {
        return scope.equals("QA")
            ? new MeetingQaException(HttpStatus.FORBIDDEN, scope + "_" + code, "Phiên tham gia không cho phép thao tác hỏi đáp này.")
            : new MeetingAccessDeniedException(scope + "_" + code);
    }
}
