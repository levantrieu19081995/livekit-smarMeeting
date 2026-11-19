package vn.cusc.meetingservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.cusc.meetingservice.config.ApiSafetyProperties;
import vn.cusc.meetingservice.exception.ApiRateLimitException;
import vn.cusc.meetingservice.exception.ErrorResponse;

public final class ApiRateLimitFilter extends OncePerRequestFilter {
    private final ApiRateLimitService budgets;
    private final ApiSafetyProperties properties;
    private final ObjectMapper json;
    public ApiRateLimitFilter(ApiRateLimitService budgets, ApiSafetyProperties properties, ObjectMapper json) {
        this.budgets = budgets; this.properties = properties; this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"POST".equals(request.getMethod()) && !"PUT".equals(request.getMethod())
            && !"PATCH".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) {
            chain.doFilter(request, response); return;
        }
        try {
            // Do not trust arbitrary X-Forwarded-For. Address budgets allow a shared corporate NAT.
            String address = ApiRateLimitService.hash(request.getRemoteAddr());
            if (path.matches("/api/auth/(login|google|register)")) {
                budgets.require("login-address:" + address, properties.getLoginPerAddressPerMinute());
            } else if (path.matches("/api/meetings/[^/]+/join")) {
                budgets.require("join-address:" + address, properties.getJoinPerAddressPerMinute());
                String actor = actor(request);
                if (actor != null) budgets.require("join-actor:" + actor, properties.getJoinPerActorPerMinute());
            } else if (path.matches("/api/meetings/[^/]+/(chat/messages|qa/.*|polls.*|speaking.*)")) {
                budgets.require("room-writes-address:" + address, properties.getChatPerAddressPerMinute());
                String actor = actor(request);
                if (actor != null) budgets.require("room-writes-actor:" + actor, properties.getWritesPerActorPerMinute());
            } else if (path.startsWith("/api/") && !path.equals("/api/auth/logout") && !path.equals("/api/livekit/webhook")) {
                budgets.require("api-writes-address:" + address, properties.getChatPerAddressPerMinute());
                String actor = actor(request);
                if (actor != null) budgets.require("api-writes-actor:" + actor, properties.getWritesPerActorPerMinute());
            }
        } catch (ApiRateLimitException exception) {
            response.setStatus(429); response.setHeader("Retry-After", "60");
            response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), new ErrorResponse(LocalDateTime.now(), 429, "Too Many Requests",
                "RATE_LIMITED", exception.getMessage(), request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }

    private String actor(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MeetingPrincipal principal)
            return "account:" + principal.publicId();
        String token = request.getHeader("X-Meeting-Participant-Token");
        if (token != null && !token.isBlank() && token.length() <= 255) return "guest:" + ApiRateLimitService.hash(token);
        return null;
    }
}
