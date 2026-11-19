package vn.cusc.meetingservice.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.config.AuthProperties;
import vn.cusc.meetingservice.config.GoogleAuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import vn.cusc.meetingservice.dto.auth.request.CreateUserRequest;
import vn.cusc.meetingservice.dto.auth.request.RegisterRequest;
import vn.cusc.meetingservice.dto.auth.request.UpdateUserRequest;
import vn.cusc.meetingservice.dto.auth.response.DirectoryUserResponse;
import vn.cusc.meetingservice.dto.auth.response.UserResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.entity.UserIdentityEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.repository.UserIdentityRepository;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.GoogleIdTokenVerifierService;
import vn.cusc.meetingservice.service.UserAccountService;

@Service
public class UserAccountServiceImpl implements UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private GoogleAuthProperties googleAuthProperties = new GoogleAuthProperties();

    @Autowired
    public void setGoogleAuthProperties(GoogleAuthProperties properties) { this.googleAuthProperties = properties; }

    public UserAccountServiceImpl(UserAccountRepository userAccountRepository,
                                  UserIdentityRepository userIdentityRepository,
                                  MeetingParticipantRepository meetingParticipantRepository,
                                  PasswordEncoder passwordEncoder,
                                  AuthProperties authProperties) {
        this.userAccountRepository = userAccountRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    @Override
    @Transactional
    public UserAccountEntity registerLocalAccount(RegisterRequest request) {
        if (!authProperties.getRegistration().isEnabled()) {
            throw new BusinessValidationException("Dang ky tai khoan dang bi tat");
        }
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedUsername = request.username().trim().toLowerCase();
        if (userAccountRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new BusinessValidationException("Email da ton tai");
        }
        if (userAccountRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessValidationException("Username da ton tai");
        }

        UserAccountEntity entity = new UserAccountEntity();
        entity.setPublicId(UUID.randomUUID().toString());
        entity.setUsername(normalizedUsername);
        entity.setNormalizedEmail(normalizedEmail);
        entity.setFullName(request.fullName().trim());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setAccountStatus(authProperties.getRegistration().isLocalAutoActivate() ? AccountStatus.ACTIVE : AccountStatus.PENDING_APPROVAL);
        entity.setSystemRole(SystemRole.USER);
        entity.setEmailVerified(false);
        entity = userAccountRepository.save(entity);

        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserAccount(entity);
        identity.setProvider(AuthProvider.LOCAL);
        identity.setProviderSubject(entity.getUsername());
        identity.setProviderEmail(entity.getNormalizedEmail());
        userIdentityRepository.save(identity);
        return entity;
    }

    @Override
    @Transactional
    public UserAccountEntity createUser(CreateUserRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedUsername = request.username().trim().toLowerCase();
        if (userAccountRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new BusinessValidationException("Email da ton tai");
        }
        if (userAccountRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessValidationException("Username da ton tai");
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setPublicId(UUID.randomUUID().toString());
        entity.setUsername(normalizedUsername);
        entity.setNormalizedEmail(normalizedEmail);
        entity.setFullName(request.fullName().trim());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setAccountStatus(request.accountStatus() == null ? AccountStatus.ACTIVE : request.accountStatus());
        entity.setSystemRole(request.systemRole());
        entity.setEmailVerified(true);
        entity = userAccountRepository.save(entity);
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserAccount(entity);
        identity.setProvider(AuthProvider.LOCAL);
        identity.setProviderSubject(entity.getUsername());
        identity.setProviderEmail(entity.getNormalizedEmail());
        userIdentityRepository.save(identity);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public UserAccountEntity findByPublicIdOrThrow(String publicId) {
        return userAccountRepository.findByPublicId(publicId)
            .orElseThrow(() -> new BusinessValidationException("Khong tim thay user " + publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public UserAccountEntity findByEmailOrThrow(String normalizedEmail) {
        return userAccountRepository.findByNormalizedEmail(normalizedEmail)
            .orElseThrow(() -> new BusinessValidationException("Khong tim thay account theo email"));
    }

    @Override
    @Transactional
    public UserAccountEntity activate(String publicId) {
        UserAccountEntity account = userAccountRepository.findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> new BusinessValidationException("Không tìm thấy tài khoản."));
        if (account.getAccountStatus() != AccountStatus.ACTIVE)
            account.setSecurityVersion(Math.incrementExact(account.getSecurityVersion()));
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setEmailVerified(true);
        return userAccountRepository.save(account);
    }

    @Override
    @Transactional
    public UserAccountEntity disable(String publicId) {
        UserAccountEntity account = userAccountRepository.findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> new BusinessValidationException("Không tìm thấy tài khoản."));
        if (account.getAccountStatus() != AccountStatus.DISABLED)
            account.setSecurityVersion(Math.incrementExact(account.getSecurityVersion()));
        account.setAccountStatus(AccountStatus.DISABLED);
        return userAccountRepository.save(account);
    }

    @Override
    @Transactional
    public UserAccountEntity update(String publicId, UpdateUserRequest request) {
        UserAccountEntity account = userAccountRepository.findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> new BusinessValidationException("Không tìm thấy tài khoản."));
        if (request.fullName() != null && !request.fullName().isBlank()) {
            account.setFullName(request.fullName().trim());
        }
        if (request.systemRole() != null && request.systemRole() != account.getSystemRole()) {
            account.setSecurityVersion(Math.incrementExact(account.getSecurityVersion()));
            account.setSystemRole(request.systemRole());
        }
        if (request.avatarUrl() != null) {
            account.setAvatarUrl(request.avatarUrl());
        }
        return userAccountRepository.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String query, AccountStatus status, Pageable pageable) {
        Page<UserResponse> page = userAccountRepository.search(query, status, pageable)
            .map(account -> new UserResponse(account.getPublicId(), account.getUsername(), account.getFullName(),
                account.getNormalizedEmail(), account.getAvatarUrl(), account.getAccountStatus().name(),
                account.getSystemRole().name(), account.isEmailVerified()));
        return new vn.cusc.meetingservice.dto.response.PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DirectoryUserResponse> directory(String query, Pageable pageable) {
        Page<DirectoryUserResponse> page = userAccountRepository.searchActive(query, pageable)
            .map(account -> new DirectoryUserResponse(account.getPublicId(), account.getUsername(), account.getFullName(),
                account.getNormalizedEmail(), account.getAvatarUrl()));
        return new vn.cusc.meetingservice.dto.response.PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }

    @Override
    public MeetingPrincipal toPrincipal(UserAccountEntity account, AuthProvider provider) {
        return new MeetingPrincipal(account.getId(), account.getPublicId(), account.getUsername(), account.getNormalizedEmail(),
            account.getFullName(), account.getAvatarUrl(), account.getAccountStatus(), account.getSystemRole(),
            account.isEmailVerified(), provider, account.getPasswordHash(), account.getSecurityVersion());
    }

    @Override
    @Transactional
    public UserAccountEntity findOrCreateGoogleAccount(GoogleIdTokenVerifierService.VerifiedGoogleToken token) {
        Optional<UserIdentityEntity> identity = userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, token.subject());
        if (identity.isPresent()) {
            return identity.get().getUserAccount();
        }

        String normalizedEmail = normalizeEmail(token.email());
        UserAccountEntity account = userAccountRepository.findByNormalizedEmail(normalizedEmail).orElseGet(() -> {
            UserAccountEntity created = new UserAccountEntity();
            created.setPublicId(UUID.randomUUID().toString());
            created.setUsername(null);
            created.setNormalizedEmail(normalizedEmail);
            created.setFullName(token.fullName());
            created.setAvatarUrl(token.avatarUrl());
            created.setAccountStatus(googleAuthProperties.isAutoProvision() ? AccountStatus.ACTIVE : AccountStatus.PENDING_APPROVAL);
            created.setSystemRole(SystemRole.USER);
            created.setEmailVerified(token.emailVerified());
            return userAccountRepository.save(created);
        });

        UserIdentityEntity newIdentity = new UserIdentityEntity();
        newIdentity.setUserAccount(account);
        newIdentity.setProvider(AuthProvider.GOOGLE);
        newIdentity.setProviderSubject(token.subject());
        newIdentity.setProviderEmail(normalizedEmail);
        userIdentityRepository.save(newIdentity);

        bindInvitationsToAccount(account);
        return account;
    }

    @Override
    @Transactional
    public void touchLastLogin(UserAccountEntity account) {
        account.setLastLoginAt(LocalDateTime.now());
        userAccountRepository.save(account);
    }

    @Override
    public String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username.trim().toLowerCase();
        UserAccountEntity account = userAccountRepository.findByUsername(normalized)
            .or(() -> userAccountRepository.findByNormalizedEmail(normalized))
            .orElseThrow(() -> new UsernameNotFoundException("Khong tim thay account"));
        return toPrincipal(account, AuthProvider.LOCAL);
    }

    @Transactional
    protected void bindInvitationsToAccount(UserAccountEntity account) {
        var matches = meetingParticipantRepository.findByInvitedEmailNormalized(account.getNormalizedEmail());
        for (MeetingParticipantEntity participant : matches) {
            if (participant.getUserAccount() == null) {
                participant.setUserAccount(account);
            }
        }
    }
}
