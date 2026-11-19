package vn.cusc.meetingservice.security;

import java.util.Objects;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.repository.UserAccountRepository;

/** Credential validation only; meeting membership and open Q&A/poll policy stay with their domain. */
@Service
public class MeetingParticipantCredentialValidator {
    private final UserAccountRepository accounts;
    private final CurrentUserService current;
    public MeetingParticipantCredentialValidator(UserAccountRepository accounts, CurrentUserService current) {
        this.accounts = accounts; this.current = current;
    }
    public void validate(MeetingParticipantConnectionEntity connection) {
        var participant = connection.getParticipant();
        if (participant == null) throw new MeetingAccessDeniedException("PARTICIPANT_IDENTITY_INVALID");
        var principal = current.getCurrentPrincipal().orElse(null);
        if (connection.getMeeting().getAccessType() == vn.cusc.meetingservice.model.enums.MeetingAccessType.PRIVATE && principal == null)
            throw new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED");
        if (participant.getUserAccount() != null) {
            var fresh = accounts.findById(participant.getUserAccount().getId()).orElse(null);
            if (!AccountCredentialVersions.matches(connection, fresh)) throw new MeetingAccessDeniedException("PARTICIPANT_CREDENTIAL_REVOKED");
            if (principal != null && (!Objects.equals(principal.accountId(), fresh.getId())
                || !AccountCredentialVersions.matches(principal, fresh)))
                throw new MeetingAccessDeniedException("PARTICIPANT_IDENTITY_MISMATCH");
        } else if (principal != null) throw new MeetingAccessDeniedException("PARTICIPANT_IDENTITY_MISMATCH");
    }
}
