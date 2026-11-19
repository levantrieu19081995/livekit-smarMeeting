package vn.cusc.meetingservice.service;

import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetailsService;
import vn.cusc.meetingservice.dto.auth.request.CreateUserRequest;
import vn.cusc.meetingservice.dto.auth.request.RegisterRequest;
import vn.cusc.meetingservice.dto.auth.request.UpdateUserRequest;
import vn.cusc.meetingservice.dto.auth.response.DirectoryUserResponse;
import vn.cusc.meetingservice.dto.auth.response.UserResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.security.MeetingPrincipal;

public interface UserAccountService extends UserDetailsService {
    UserAccountEntity registerLocalAccount(RegisterRequest request);
    UserAccountEntity createUser(CreateUserRequest request);
    UserAccountEntity findByPublicIdOrThrow(String publicId);
    UserAccountEntity findByEmailOrThrow(String normalizedEmail);
    UserAccountEntity activate(String publicId);
    UserAccountEntity disable(String publicId);
    UserAccountEntity update(String publicId, UpdateUserRequest request);
    PageResponse<UserResponse> search(String query, AccountStatus status, Pageable pageable);
    PageResponse<DirectoryUserResponse> directory(String query, Pageable pageable);
    MeetingPrincipal toPrincipal(UserAccountEntity account, AuthProvider provider);
    UserAccountEntity findOrCreateGoogleAccount(GoogleIdTokenVerifierService.VerifiedGoogleToken token);
    void touchLastLogin(UserAccountEntity account);
    String normalizeEmail(String email);
}
