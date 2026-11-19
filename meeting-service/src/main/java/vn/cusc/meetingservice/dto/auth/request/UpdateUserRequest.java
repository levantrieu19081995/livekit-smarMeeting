package vn.cusc.meetingservice.dto.auth.request;

import jakarta.validation.constraints.Size;
import vn.cusc.meetingservice.model.enums.SystemRole;

public record UpdateUserRequest(
    @Size(max = 255) String fullName,
    SystemRole systemRole,
    String avatarUrl
) {
}
