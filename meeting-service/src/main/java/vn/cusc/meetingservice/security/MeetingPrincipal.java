package vn.cusc.meetingservice.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.SystemRole;

public record MeetingPrincipal(
    Long accountId,
    String publicId,
    String username,
    String normalizedEmail,
    String fullName,
    String avatarUrl,
    AccountStatus accountStatus,
    SystemRole systemRole,
    boolean emailVerified,
    AuthProvider provider,
    String passwordHash,
    long securityVersion
) implements UserDetails, Serializable {

    public MeetingPrincipal(Long accountId, String publicId, String username, String normalizedEmail,
        String fullName, String avatarUrl, AccountStatus accountStatus, SystemRole systemRole, boolean emailVerified,
        AuthProvider provider, String passwordHash) {
        this(accountId, publicId, username, normalizedEmail, fullName, avatarUrl, accountStatus, systemRole,
            emailVerified, provider, passwordHash, 0);
    }

    public MeetingPrincipal withoutPassword() {
        return new MeetingPrincipal(accountId, publicId, username, normalizedEmail, fullName, avatarUrl, accountStatus,
            systemRole, emailVerified, provider, null, securityVersion);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + systemRole.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username != null && !username.isBlank() ? username : normalizedEmail;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountStatus != AccountStatus.DISABLED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return accountStatus == AccountStatus.ACTIVE;
    }
}
