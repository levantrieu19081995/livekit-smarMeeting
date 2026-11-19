package vn.cusc.meetingservice.service;

import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingVisibility;
import vn.cusc.meetingservice.util.MeetingPermissions;

@Service
public class MeetingMinutesAuthorizationService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository participantRepository;
    private final UserAccountRepository accountRepository;
    private final CurrentUserService currentUserService;

    public MeetingMinutesAuthorizationService(
        MeetingRepository meetingRepository,
        MeetingParticipantRepository participantRepository,
        UserAccountRepository accountRepository,
        CurrentUserService currentUserService
    ) {
        this.meetingRepository = meetingRepository;
        this.participantRepository = participantRepository;
        this.accountRepository = accountRepository;
        this.currentUserService = currentUserService;
    }

    public MeetingPrincipal requirePrincipal() {
        return currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
    }

    public void requireListAccess() {
        requirePrincipal();
    }

    public MeetingEntity requireMeetingView(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        MeetingPrincipal principal = requirePrincipal();
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meetingId, principal.accountId()).orElse(null);
        if (!MeetingVisibility.canView(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("FORBIDDEN");
        }
        return meeting;
    }

    public MeetingEntity requireMeetingAccess(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        requireMeetingAccess(meeting);
        return meeting;
    }

    public void requireMeetingAccess(MeetingEntity meeting) {
        MeetingPrincipal principal = requirePrincipal();
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId())
            .orElse(null);
        if (!MeetingPermissions.canManage(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("MINUTES_MANAGEMENT_FORBIDDEN");
        }
    }

    public MeetingRole currentMeetingRole(Long meetingId) {
        MeetingPrincipal principal = requirePrincipal();
        return participantRepository.findByMeetingIdAndUserAccountId(meetingId, principal.accountId())
            .map(MeetingParticipantEntity::getRole)
            .orElse(null);
    }

    public UserAccountEntity requireCurrentAccount() {
        MeetingPrincipal principal = requirePrincipal();
        return accountRepository.findById(principal.accountId())
            .orElseThrow(() -> new MeetingAccessDeniedException("ACCOUNT_NOT_FOUND"));
    }

    public MinutesVersionSource resolveVersionSource(Long meetingId) {
        MeetingPrincipal principal = requirePrincipal();
        MeetingEntity meeting = requireMeetingAccess(meetingId);
        MeetingRole role = currentMeetingRole(meetingId);
        if (meeting.getCreatedByAccount() != null
            && principal.accountId().equals(meeting.getCreatedByAccount().getId())) {
            return MinutesVersionSource.OWNER_EDITED;
        }
        if (role == MeetingRole.SECRETARY) {
            return MinutesVersionSource.SECRETARY_EDITED;
        }
        if (role == MeetingRole.CHAIRMAN) {
            return MinutesVersionSource.CHAIRMAN_EDITED;
        }
        throw new MeetingAccessDeniedException("MINUTES_EDIT_FORBIDDEN");
    }

}
