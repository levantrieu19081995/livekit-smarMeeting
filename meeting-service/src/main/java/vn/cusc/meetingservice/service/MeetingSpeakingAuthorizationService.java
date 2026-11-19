package vn.cusc.meetingservice.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.MeetingSpeakingException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.security.*;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@Service @RequiredArgsConstructor
public class MeetingSpeakingAuthorizationService {
    private final CurrentUserService current;
    private final MeetingParticipantRepository participants;
    private final MeetingParticipantConnectionRepository connections;
    private final UserAccountRepository accounts;
    private final MeetingStatusResolver statusResolver;

    public record Actor(MeetingPrincipal principal, MeetingParticipantEntity participant,
        MeetingParticipantConnectionEntity connection, boolean canModerate, String displayName) { }

    public Actor viewer(MeetingEntity meeting, String token) {
        var principal = current.getCurrentPrincipal().orElse(null);
        UserAccountEntity account = null;
        if (principal != null) {
            account = accounts.findById(principal.accountId()).orElse(null);
            if (!vn.cusc.meetingservice.security.AccountCredentialVersions.matches(principal, account)) {
                denied("SPEAKING_ACCOUNT_DISABLED", "Tài khoản không còn hoạt động.");
            }
        }
        MeetingParticipantEntity participant = principal == null ? null
            : participants.findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId()).orElse(null);
        MeetingParticipantConnectionEntity connection = null;
        if (token != null) {
            if (token.isBlank() || token.length() > 255) denied("SPEAKING_INVALID_PARTICIPANT_TOKEN", "Phiên tham gia không hợp lệ.");
            connection = connections.findByMeetingIdAndChatTokenHash(meeting.getId(), MeetingChatTokenUtils.sha256(token))
                .orElseThrow(() -> forbidden("SPEAKING_INVALID_PARTICIPANT_TOKEN", "Phiên tham gia không hợp lệ."));
            participant = connection.getParticipant();
            boolean terminal = terminal(meeting);
            if (connection.getStatus() != ParticipantConnectionStatus.PENDING
                && connection.getStatus() != ParticipantConnectionStatus.CONNECTED
                && !(terminal && connection.getStatus() == ParticipantConnectionStatus.LEFT)) {
                denied("SPEAKING_CONNECTION_NOT_CONNECTED", "Phiên tham gia không còn kết nối.");
            }
            if (participant == null || !Objects.equals(participant.getMeeting().getId(), meeting.getId())
                || participant.getStatus() == ParticipantStatus.REMOVED) {
                denied("SPEAKING_PARTICIPANT_REMOVED", "Người tham gia không còn được phép truy cập.");
            }
            if (participant.getUserAccount() != null) {
                var fresh = accounts.findById(participant.getUserAccount().getId()).orElse(null);
                if (!vn.cusc.meetingservice.security.AccountCredentialVersions.matches(connection, fresh))
                    denied("SPEAKING_ACCOUNT_DISABLED", "Tài khoản không còn hoạt động.");
            }
            if (principal != null && (participant.getUserAccount() == null
                || !Objects.equals(principal.accountId(), participant.getUserAccount().getId()))) {
                denied("SPEAKING_PARTICIPANT_IDENTITY_MISMATCH", "Phiên tham gia không thuộc tài khoản đăng nhập.");
            }
        } else {
            // The shared resolver enforces meeting visibility for signed-in viewers.
            var validated = MeetingParticipantAccess.viewer(meeting, null, "SPEAKING", current,
                participants, connections, accounts);
            participant = validated.participant();
        }
        boolean owner = meeting.getCreatedByAccount() != null && (principal != null
            && Objects.equals(principal.accountId(), meeting.getCreatedByAccount().getId())
            || connection != null && participant.getUserAccount() != null
                && Objects.equals(participant.getUserAccount().getId(), meeting.getCreatedByAccount().getId()));
        boolean officer = participant != null && participant.getStatus() != ParticipantStatus.REMOVED
            && (participant.getUserAccount() != null || connection != null)
            && (participant.getRole() == MeetingRole.CHAIRMAN || participant.getRole() == MeetingRole.SECRETARY);
        String name = participant != null ? participant.getFullName() : account == null ? null : account.getFullName();
        return new Actor(principal, participant, connection, owner || officer, name);
    }

    public void requireJoined(Actor actor) {
        var connection = actor.connection();
        if (connection == null || connection.getPoint() == null || (connection.getStatus() != ParticipantConnectionStatus.PENDING
            && connection.getStatus() != ParticipantConnectionStatus.CONNECTED)) {
            denied("SPEAKING_JOIN_REQUIRED", "Vui lòng vào cuộc họp và chọn điểm cầu trước khi xin phát biểu.");
        }
    }

    public boolean terminal(MeetingEntity meeting) {
        var status = statusResolver.resolve(meeting);
        return status == MeetingStatus.COMPLETED || status == MeetingStatus.CANCELLED;
    }
    private static void denied(String code, String message) { throw forbidden(code, message); }
    private static MeetingSpeakingException forbidden(String code, String message) {
        return new MeetingSpeakingException(HttpStatus.FORBIDDEN, code, message);
    }
}
