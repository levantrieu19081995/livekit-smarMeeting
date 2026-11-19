package vn.cusc.meetingservice.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.cusc.meetingservice.config.AuthProperties;
import vn.cusc.meetingservice.config.GoogleAuthProperties;
import vn.cusc.meetingservice.config.InternalSsoProperties;
import vn.cusc.meetingservice.dto.auth.request.GoogleLoginRequest;
import vn.cusc.meetingservice.dto.auth.request.LoginRequest;
import vn.cusc.meetingservice.dto.auth.request.RegisterRequest;
import vn.cusc.meetingservice.dto.auth.response.AuthMeResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthProviderResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthSessionResponse;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.security.ApiRateLimitService;
import vn.cusc.meetingservice.service.AuthService;
import vn.cusc.meetingservice.service.GoogleIdTokenVerifierService;
import vn.cusc.meetingservice.service.UserAccountService;

@Service
public class AuthServiceImpl implements AuthService {

    private final CurrentUserService currentUserService;
    private final UserAccountService userAccountService;
    private final AuthenticationManager authenticationManager;
    private final GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private final AuthProperties authProperties;
    private final GoogleAuthProperties googleAuthProperties;
    private final InternalSsoProperties internalSsoProperties;
    private ApiRateLimitService rateLimits;

    @Autowired
    public void setRateLimits(ApiRateLimitService rateLimits) { this.rateLimits = rateLimits; }

    public AuthServiceImpl(CurrentUserService currentUserService,
                           UserAccountService userAccountService,
                           AuthenticationManager authenticationManager,
                           GoogleIdTokenVerifierService googleIdTokenVerifierService,
                           AuthProperties authProperties,
                           GoogleAuthProperties googleAuthProperties,
                           InternalSsoProperties internalSsoProperties) {
        this.currentUserService = currentUserService;
        this.userAccountService = userAccountService;
        this.authenticationManager = authenticationManager;
        this.googleIdTokenVerifierService = googleIdTokenVerifierService;
        this.authProperties = authProperties;
        this.googleAuthProperties = googleAuthProperties;
        this.internalSsoProperties = internalSsoProperties;
    }

    @Override
    public AuthMeResponse me() {
        return currentUserService.getCurrentPrincipal()
            .map(this::toMeResponse)
            .orElseGet(() -> new AuthMeResponse(false, null));
    }

    @Override
    public AuthProviderResponse providers() {
        return new AuthProviderResponse(true, authProperties.getRegistration().isEnabled(),
            googleAuthProperties.isEnabled(), googleAuthProperties.getClientId(), internalSsoProperties.isEnabled());
    }

    @Override
    @Transactional
    public AuthSessionResponse register(RegisterRequest request) {
        UserAccountEntity account = userAccountService.registerLocalAccount(request);
        return new AuthSessionResponse(false, new AuthSessionResponse.AuthUserResponse(
            account.getPublicId(), account.getUsername(), account.getFullName(), account.getNormalizedEmail(),
            account.getAvatarUrl(), AuthProvider.LOCAL.name(), account.getSystemRole().name()));
    }

    @Override
    @Transactional
    public AuthSessionResponse login(LoginRequest request) {
        if (rateLimits != null) rateLimits.loginIdentity(request.usernameOrEmail());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.usernameOrEmail(), request.password())
            );
        } catch (BadCredentialsException exception) {
            throw new BusinessValidationException("Sai thông tin đăng nhập");
        } catch (org.springframework.security.authentication.LockedException exception) {
            throw new BusinessValidationException("Tài khoản đã bị khóa");
        } catch (org.springframework.security.authentication.AccountStatusException exception) {
            throw new BusinessValidationException("Tài khoản chưa được kích hoạt hoặc đang chờ duyệt");
        }
        MeetingPrincipal principal = (MeetingPrincipal) authentication.getPrincipal();
        if (principal.accountStatus() == AccountStatus.PENDING_APPROVAL) {
            throw new BusinessValidationException("Tài khoản đang chờ duyệt");
        }
        if (principal.accountStatus() == AccountStatus.DISABLED) {
            throw new BusinessValidationException("Tài khoản đã bị khóa");
        }
        UserAccountEntity account = userAccountService.findByPublicIdOrThrow(principal.publicId());
        userAccountService.touchLastLogin(account);
        storeAfterCommit(authentication);
        return new AuthSessionResponse(true, toUserResponse(principal));
    }

    @Override
    @Transactional
    public AuthSessionResponse googleLogin(GoogleLoginRequest request) {
        var token = googleIdTokenVerifierService.verify(request.credential());
        UserAccountEntity account = userAccountService.findOrCreateGoogleAccount(token);
        MeetingPrincipal principal = userAccountService.toPrincipal(account, AuthProvider.GOOGLE);
        if (account.getAccountStatus() == AccountStatus.DISABLED) {
            throw new BusinessValidationException("Tài khoản đã bị khóa");
        }
        if (account.getAccountStatus() == AccountStatus.PENDING_APPROVAL) {
            return new AuthSessionResponse(false, toUserResponse(principal));
        }
        userAccountService.touchLastLogin(account);
        storeAfterCommit(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        return new AuthSessionResponse(true, toUserResponse(principal));
    }

    private void storeAfterCommit(Authentication authentication) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { storeAuthentication(authentication); }
        });
        else storeAuthentication(authentication);
    }

    private void storeAuthentication(Authentication authentication) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        var response = attributes.getResponse();
        if (response == null) throw new IllegalStateException("Authentication requires an HTTP response");
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN"); csrf.setHeaderName("X-XSRF-TOKEN"); csrf.setCookiePath("/");
        csrf.saveToken(null, request, response);
        if (authentication.getPrincipal() instanceof MeetingPrincipal principal) {
            var safe = principal.withoutPassword();
            authentication = UsernamePasswordAuthenticationToken.authenticated(safe, null, safe.getAuthorities());
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }

    private AuthMeResponse toMeResponse(MeetingPrincipal principal) {
        return new AuthMeResponse(true, toUserResponse(principal));
    }

    private AuthSessionResponse.AuthUserResponse toUserResponse(MeetingPrincipal principal) {
        return new AuthSessionResponse.AuthUserResponse(
            principal.publicId(),
            principal.username(),
            principal.fullName(),
            principal.normalizedEmail(),
            principal.avatarUrl(),
            principal.provider().name(),
            principal.systemRole().name()
        );
    }
}
