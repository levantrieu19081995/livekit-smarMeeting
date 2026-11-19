package vn.cusc.meetingservice.dto.auth.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
    @NotBlank(message = "credential is required") String credential
) {
}
