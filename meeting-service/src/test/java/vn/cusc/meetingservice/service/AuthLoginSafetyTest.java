package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;
import vn.cusc.meetingservice.config.*;
import vn.cusc.meetingservice.dto.auth.request.GoogleLoginRequest;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.security.*;
import vn.cusc.meetingservice.service.impl.AuthServiceImpl;

class AuthLoginSafetyTest {
    private final CurrentUserService current = mock(CurrentUserService.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final GoogleIdTokenVerifierService google = mock(GoogleIdTokenVerifierService.class);
    private final GoogleAuthProperties properties = new GoogleAuthProperties();
    private final AuthServiceImpl service = new AuthServiceImpl(current, accounts, mock(AuthenticationManager.class), google,
        new AuthProperties(), properties, new InternalSsoProperties());
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    @BeforeEach void http() {
        request = new MockHttpServletRequest(); response = new MockHttpServletResponse(); request.setSession(new MockHttpSession());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); SecurityContextHolder.clearContext(); }
    @Test void disabledGoogleAccountNeverCreatesAnAuthenticatedSession() {
        setup(AccountStatus.DISABLED); properties.setAutoProvision(true);
        assertThrows(BusinessValidationException.class, () -> service.googleLogin(new GoogleLoginRequest("verified")));
        assertNull(SecurityContextHolder.getContext().getAuthentication()); verify(accounts, never()).touchLastLogin(any());
    }
    @Test void existingPendingAccountStillWaitsForApprovalWithAutoProvisionEnabled() {
        setup(AccountStatus.PENDING_APPROVAL); properties.setAutoProvision(true);
        assertFalse(service.googleLogin(new GoogleLoginRequest("verified")).authenticated());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void manualGoogleLoginRotatesExistingSessionAndClearsOldCsrfToken() {
        setup(AccountStatus.ACTIVE); String old = request.getSession().getId();
        assertTrue(service.googleLogin(new GoogleLoginRequest("verified")).authenticated());
        assertNotEquals(old, request.getSession().getId());
        var principal = (MeetingPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertNull(principal.passwordHash()); assertEquals(3, principal.securityVersion());
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(value -> value.startsWith("XSRF-TOKEN=") && value.contains("Max-Age=0")));
    }
    private void setup(AccountStatus status) {
        var token = new GoogleIdTokenVerifierService.VerifiedGoogleToken("subject", "a@example.test", "Actor", null, true, "issuer", "audience");
        var account = new UserAccountEntity(); account.setId(1L); account.setPublicId("actor"); account.setAccountStatus(status);
        when(google.verify("verified")).thenReturn(token); when(accounts.findOrCreateGoogleAccount(token)).thenReturn(account);
        when(accounts.toPrincipal(account, AuthProvider.GOOGLE)).thenReturn(new MeetingPrincipal(1L, "actor", "actor", "a@example.test", "Actor", null,
            status, SystemRole.USER, true, AuthProvider.GOOGLE, "must-not-be-in-session", 3));
    }
}
