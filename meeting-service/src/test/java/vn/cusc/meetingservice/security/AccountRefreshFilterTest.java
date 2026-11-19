package vn.cusc.meetingservice.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.UserAccountRepository;

class AccountRefreshFilterTest {
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccountRefreshFilter filter = new AccountRefreshFilter(() -> accounts,
        JsonMapper.builder().addModule(new JavaTimeModule()).build());
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void refreshesGlobalAuthoritiesBeforeUrlAuthorizationWithoutRetainingPassword() throws Exception {
        var account = account(); account.setSystemRole(SystemRole.USER);
        when(accounts.findById(1L)).thenReturn(Optional.of(account)); login(0);
        var chain = mock(FilterChain.class); var request = new MockHttpServletRequest("GET", "/api/users"); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("ROLE_USER", authentication.getAuthorities().iterator().next().getAuthority());
        assertNull(((MeetingPrincipal) authentication.getPrincipal()).passwordHash()); verify(chain).doFilter(request, response);
    }
    @Test void disabledAccountInvalidatesSessionAndCannotFallBackToPublicGuest() throws Exception {
        var account = account(); account.setAccountStatus(AccountStatus.DISABLED);
        when(accounts.findById(1L)).thenReturn(Optional.of(account)); login(0);
        var chain = mock(FilterChain.class); var request = new MockHttpServletRequest("POST", "/api/meetings/1/join");
        var session = new MockHttpSession(); request.setSession(session); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus()); assertTrue(session.isInvalid()); verifyNoInteractions(chain);
        assertTrue(response.getContentAsString().contains("ACCOUNT_SESSION_REVOKED"));
    }
    @Test void reEnabledAccountDoesNotRevivePreDisableSession() throws Exception {
        var account = account(); account.setSecurityVersion(1); when(accounts.findById(1L)).thenReturn(Optional.of(account)); login(0);
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, chain);
        assertEquals(401, response.getStatus()); verifyNoInteractions(chain);
    }
    @Test void validNewEpochSessionContinues() throws Exception {
        var account = account(); account.setSecurityVersion(1); when(accounts.findById(1L)).thenReturn(Optional.of(account)); login(1);
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class); var request = new MockHttpServletRequest();
        filter.doFilter(request, response, chain); verify(chain).doFilter(request, response);
    }
    @Test void anonymousGuestsDoNotRequireAnAccountLookup() throws Exception {
        var chain = mock(FilterChain.class); var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain); verify(chain).doFilter(request, response); verifyNoInteractions(accounts);
    }
    @Test void accountStoreOutageFailsClosedWithoutDestroyingAValidSession() throws Exception {
        when(accounts.findById(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("password=secret")); login(0);
        var request = new MockHttpServletRequest("POST", "/api/meetings/1/join"); var session = new MockHttpSession(); request.setSession(session);
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class); filter.doFilter(request, response, chain);
        assertEquals(503, response.getStatus()); assertFalse(session.isInvalid()); assertFalse(response.getContentAsString().contains("password")); verifyNoInteractions(chain);
    }
    private void login(long epoch) {
        var principal = new MeetingPrincipal(1L, "actor-1", "actor", "a@example.test", "Actor", null,
            AccountStatus.ACTIVE, SystemRole.ADMIN, true, AuthProvider.LOCAL, "never-keep-this", epoch);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
    private UserAccountEntity account() {
        var value = new UserAccountEntity(); value.setId(1L); value.setPublicId("actor-1"); value.setUsername("actor");
        value.setNormalizedEmail("a@example.test"); value.setFullName("Actor"); value.setAccountStatus(AccountStatus.ACTIVE);
        value.setSystemRole(SystemRole.ADMIN); return value;
    }
}
