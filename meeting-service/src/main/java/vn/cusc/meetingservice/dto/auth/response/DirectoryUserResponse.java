package vn.cusc.meetingservice.dto.auth.response;

public record DirectoryUserResponse(
    String publicId,
    String username,
    String fullName,
    String email,
    String avatarUrl
) {
}
