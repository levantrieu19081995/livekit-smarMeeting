package vn.cusc.meetingservice.controller;

// Đăng ký, đăng nhập, Google login, logout, CSRF, session hiện tại
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.auth.request.GoogleLoginRequest;
import vn.cusc.meetingservice.dto.auth.request.LoginRequest;
import vn.cusc.meetingservice.dto.auth.request.RegisterRequest;
import vn.cusc.meetingservice.dto.auth.response.AuthMeResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthProviderResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthSessionResponse;
import vn.cusc.meetingservice.service.AuthService;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current session user")
    public AuthMeResponse me() {
        return authService.me();
    }

    @GetMapping("/providers")
    @Operation(summary = "Get enabled auth providers")
    public AuthProviderResponse providers() {
        return authService.providers();
    }

    @GetMapping("/csrf")
    @Operation(summary = "Get csrf token")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName(), "parameterName", csrfToken.getParameterName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register local account")
    public AuthSessionResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Login with local account")
    public AuthSessionResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    @Operation(summary = "Login with Google ID token")
    public AuthSessionResponse google(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Logout current session")
    public void logout() {
    }
}
