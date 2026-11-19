package vn.cusc.meetingservice.dto.auth.response;

public record AuthMeResponse(
    boolean authenticated,
    AuthSessionResponse.AuthUserResponse user
) {
}
