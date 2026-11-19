package vn.cusc.meetingservice.controller;


// Quản trị tài khoản
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.auth.request.CreateUserRequest;
import vn.cusc.meetingservice.dto.auth.request.UpdateUserRequest;
import vn.cusc.meetingservice.dto.auth.response.UserResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.service.UserAccountService;
import vn.cusc.meetingservice.util.ApiPageRequests;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
public class UserAdminController {

    private final UserAccountService userAccountService;

    public UserAdminController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @GetMapping
    @Operation(summary = "Search users")
    public PageResponse<UserResponse> search(@RequestParam(required = false) String query,
                                             @RequestParam(required = false) AccountStatus status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        ApiPageRequests.query(query);
        return userAccountService.search(query, status, ApiPageRequests.bounded(page, size, Sort.by("fullName").and(Sort.by("id"))));
    }

    @GetMapping("/{publicId}")
    @Operation(summary = "Get user by public id")
    public UserResponse getById(@PathVariable String publicId) {
        var user = userAccountService.findByPublicIdOrThrow(publicId);
        return new UserResponse(user.getPublicId(), user.getUsername(), user.getFullName(), user.getNormalizedEmail(),
            user.getAvatarUrl(), user.getAccountStatus().name(), user.getSystemRole().name(), user.isEmailVerified());
    }

    @PostMapping
    @Operation(summary = "Create user")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        var user = userAccountService.createUser(request);
        return new UserResponse(user.getPublicId(), user.getUsername(), user.getFullName(), user.getNormalizedEmail(),
            user.getAvatarUrl(), user.getAccountStatus().name(), user.getSystemRole().name(), user.isEmailVerified());
    }

    @PatchMapping("/{publicId}")
    @Operation(summary = "Update user")
    public UserResponse update(@PathVariable String publicId, @Valid @RequestBody UpdateUserRequest request) {
        var user = userAccountService.update(publicId, request);
        return new UserResponse(user.getPublicId(), user.getUsername(), user.getFullName(), user.getNormalizedEmail(),
            user.getAvatarUrl(), user.getAccountStatus().name(), user.getSystemRole().name(), user.isEmailVerified());
    }

    @PostMapping("/{publicId}/activate")
    @Operation(summary = "Activate user")
    public UserResponse activate(@PathVariable String publicId) {
        var user = userAccountService.activate(publicId);
        return new UserResponse(user.getPublicId(), user.getUsername(), user.getFullName(), user.getNormalizedEmail(),
            user.getAvatarUrl(), user.getAccountStatus().name(), user.getSystemRole().name(), user.isEmailVerified());
    }

    @PostMapping("/{publicId}/disable")
    @Operation(summary = "Disable user")
    public UserResponse disable(@PathVariable String publicId) {
        var user = userAccountService.disable(publicId);
        return new UserResponse(user.getPublicId(), user.getUsername(), user.getFullName(), user.getNormalizedEmail(),
            user.getAvatarUrl(), user.getAccountStatus().name(), user.getSystemRole().name(), user.isEmailVerified());
    }
}
