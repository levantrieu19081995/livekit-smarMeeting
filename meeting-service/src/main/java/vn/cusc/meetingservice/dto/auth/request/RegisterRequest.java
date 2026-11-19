package vn.cusc.meetingservice.dto.auth.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "username is required") @Size(max = 100) String username,
    @NotBlank(message = "fullName is required") @Size(max = 255) String fullName,
    @NotBlank(message = "email is required") @Email(message = "email is invalid") @Size(max = 255) String email,
    @NotBlank(message = "password is required") @Size(min = 8, max = 100) String password
) {
}
