package vn.cusc.meetingservice.dto.auth.response;

public record AuthSessionResponse(
    boolean authenticated,
    AuthUserResponse user
) {
    public record AuthUserResponse(
        String publicId,
        String username,
        String fullName,
        String email,
        String avatarUrl,
        String provider,
        String systemRole
    ) {
    }
}
