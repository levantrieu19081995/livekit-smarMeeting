package vn.cusc.meetingservice.dto.auth.response;

public record UserResponse(
    String publicId,
    String username,
    String fullName,
    String email,
    String avatarUrl,
    String accountStatus,
    String systemRole,
    boolean emailVerified
) {
}
