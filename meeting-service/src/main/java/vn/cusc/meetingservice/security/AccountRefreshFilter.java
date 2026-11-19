package vn.cusc.meetingservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.cusc.meetingservice.exception.ErrorResponse;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.repository.UserAccountRepository;

/** Refresh account state before URL authorization; a disabled session cannot become a guest request. */
public final class AccountRefreshFilter extends OncePerRequestFilter {
    private final Supplier<UserAccountRepository> repositories;
    private final ObjectMapper json;

    public AccountRefreshFilter(Supplier<UserAccountRepository> repositories, ObjectMapper json) {
        this.repositories = repositories;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        if (previous != null && previous.isAuthenticated() && previous.getPrincipal() instanceof MeetingPrincipal old) {
            var repository = repositories.get();
            vn.cusc.meetingservice.entity.UserAccountEntity account;
            try { account = repository == null ? null : repository.findById(old.accountId()).orElse(null); }
            catch (org.springframework.dao.DataAccessException unavailable) {
                response.setStatus(503); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                json.writeValue(response.getWriter(), new ErrorResponse(LocalDateTime.now(), 503, "Service Unavailable",
                    "ACCOUNT_VERIFICATION_UNAVAILABLE", "Không thể kiểm tra phiên đăng nhập. Vui lòng thử lại.", request.getRequestURI()));
                return;
            }
            if (account == null || account.getAccountStatus() != AccountStatus.ACTIVE
                || !old.publicId().equals(account.getPublicId()) || old.securityVersion() != account.getSecurityVersion()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.setStatus(401);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                json.writeValue(response.getWriter(), new ErrorResponse(LocalDateTime.now(), 401, "Unauthorized",
                    "ACCOUNT_SESSION_REVOKED", "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.", request.getRequestURI()));
                return;
            }
            var fresh = new MeetingPrincipal(account.getId(), account.getPublicId(), account.getUsername(),
                account.getNormalizedEmail(), account.getFullName(), account.getAvatarUrl(), account.getAccountStatus(),
                account.getSystemRole(), account.isEmailVerified(), old.provider(), null, account.getSecurityVersion());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities());
            authentication.setDetails(previous.getDetails());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
