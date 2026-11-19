package vn.cusc.meetingservice.service;

import vn.cusc.meetingservice.dto.auth.request.GoogleLoginRequest;
import vn.cusc.meetingservice.dto.auth.request.LoginRequest;
import vn.cusc.meetingservice.dto.auth.request.RegisterRequest;
import vn.cusc.meetingservice.dto.auth.response.AuthMeResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthProviderResponse;
import vn.cusc.meetingservice.dto.auth.response.AuthSessionResponse;

public interface AuthService {
    AuthMeResponse me();
    AuthProviderResponse providers();
    AuthSessionResponse register(RegisterRequest request);
    AuthSessionResponse login(LoginRequest request);
    AuthSessionResponse googleLogin(GoogleLoginRequest request);
}
