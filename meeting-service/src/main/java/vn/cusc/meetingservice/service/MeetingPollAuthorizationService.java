package vn.cusc.meetingservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingParticipantAccess;
import vn.cusc.meetingservice.security.MeetingParticipantAccess.Actor;

@Service
@RequiredArgsConstructor
public class MeetingPollAuthorizationService {
    private final CurrentUserService currentUser;
    private final MeetingParticipantRepository participants;
    private final MeetingParticipantConnectionRepository connections;
    private final UserAccountRepository accounts;

    public Actor requireViewer(MeetingEntity meeting, String token) {
        return MeetingParticipantAccess.viewer(meeting, token, "POLL", currentUser, participants, connections, accounts);
    }

    public Actor requireManager(MeetingEntity meeting, String token) {
        Actor actor = requireViewer(meeting, token);
        if (!actor.canManage(meeting)) throw new MeetingAccessDeniedException("POLL_PARTICIPANT_REQUIRED");
        return actor;
    }

    public boolean canBeEligible(MeetingParticipantEntity participant) { return active(participant); }
    public boolean active(MeetingParticipantEntity participant) { return MeetingParticipantAccess.active(participant); }
}
