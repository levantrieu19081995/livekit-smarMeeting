package vn.cusc.meetingservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingQaException;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingParticipantAccess;

@Service
@RequiredArgsConstructor
public class MeetingQaAuthorizationService {
    private final CurrentUserService currentUser;
    private final MeetingParticipantRepository participants;
    private final MeetingParticipantConnectionRepository connections;
    private final UserAccountRepository accounts;

    public record Actor(MeetingParticipantEntity participant, boolean canManage, boolean hasParticipantToken,
        String displayName) { }

    public Actor viewer(MeetingEntity meeting, String token) {
        var actor = MeetingParticipantAccess.viewer(meeting, token, "QA", currentUser, participants, connections, accounts);
        String name = actor.participant() != null ? actor.participant().getFullName()
            : actor.principal() != null ? actor.principal().fullName() : null;
        return new Actor(actor.participant(), actor.canManage(meeting), actor.hasParticipantToken(),
            name == null || name.isBlank() ? "Người tham gia" : name);
    }

    public void requireParticipantToken(Actor actor) {
        if (!actor.hasParticipantToken() || actor.participant() == null)
            throw new MeetingQaException(HttpStatus.FORBIDDEN, "QA_PARTICIPANT_TOKEN_REQUIRED", "Bạn cần vào cuộc họp để gửi câu hỏi.");
    }
}
