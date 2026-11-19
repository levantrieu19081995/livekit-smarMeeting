package vn.cusc.meetingservice.dto.auth.response;

public record AuthProviderResponse(
    boolean localEnabled,
    boolean registrationEnabled,
    boolean googleEnabled,
    String googleClientId,
    boolean internalSsoEnabled
) {
}
