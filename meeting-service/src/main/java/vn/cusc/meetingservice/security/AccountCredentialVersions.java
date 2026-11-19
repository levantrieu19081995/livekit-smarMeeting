package vn.cusc.meetingservice.security;

import java.util.Objects;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.AccountStatus;

public final class AccountCredentialVersions {
    private AccountCredentialVersions() { }
    public static boolean matches(MeetingPrincipal principal, UserAccountEntity account) {
        return principal != null && account != null && principal.isEnabled()
            && account.getAccountStatus() == AccountStatus.ACTIVE
            && Objects.equals(principal.accountId(), account.getId()) && Objects.equals(principal.publicId(), account.getPublicId())
            && principal.securityVersion() == account.getSecurityVersion();
    }
    public static boolean matches(MeetingParticipantConnectionEntity connection, UserAccountEntity account) {
        // Legacy credentials are version zero only; re-enabling an account never revives old credentials.
        long issued = connection.getAccountSecurityVersion() == null ? 0 : connection.getAccountSecurityVersion();
        return account != null && account.getAccountStatus() == AccountStatus.ACTIVE && issued == account.getSecurityVersion();
    }
}
