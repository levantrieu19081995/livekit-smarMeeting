package vn.cusc.meetingservice.service;

import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.util.MeetingVisibility;
import vn.cusc.meetingservice.util.MeetingPermissions;

@Service
public class MeetingAccessPolicyService {

    private final CurrentUserService currentUserService;
    private final MeetingParticipantRepository participantRepository;

    public MeetingAccessPolicyService(CurrentUserService currentUserService,
                                      MeetingParticipantRepository participantRepository) {
        this.currentUserService = currentUserService;
        this.participantRepository = participantRepository;
    }

    public boolean canManageMeeting(MeetingPrincipal principal, MeetingEntity meeting, MeetingParticipantEntity participant) {
        return MeetingPermissions.canManage(principal, meeting, participant);
    }

    public boolean canEndMeeting(MeetingPrincipal principal, MeetingEntity meeting, MeetingParticipantEntity participant) {
        return canManageMeeting(principal, meeting, participant);
    }

    public boolean canViewMeeting(MeetingPrincipal principal, MeetingEntity meeting, MeetingParticipantEntity participant) {
        return MeetingVisibility.canView(principal, meeting, participant);
    }

    public void requireCanManageMeeting(MeetingEntity meeting) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId())
            .orElse(null);
        if (!canManageMeeting(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("FORBIDDEN");
        }
    }

    public void requireCanEndMeeting(MeetingEntity meeting) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId())
            .orElse(null);
        if (!canEndMeeting(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("FORBIDDEN");
        }
    }

    public void requireCanViewMeeting(MeetingEntity meeting) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId())
            .orElse(null);
        if (!canViewMeeting(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("FORBIDDEN");
        }
    }
}
