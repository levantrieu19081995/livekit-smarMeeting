package vn.cusc.meetingservice.controller;


// Tìm người dùng để mời họp
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.auth.response.DirectoryUserResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.service.UserAccountService;
import vn.cusc.meetingservice.util.ApiPageRequests;

@RestController
@RequestMapping("/api/directory/users")
@Tag(name = "Directory")
public class UserDirectoryController {

    private final UserAccountService userAccountService;

    public UserDirectoryController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @GetMapping
    @Operation(summary = "Search active users for participant selection")
    public PageResponse<DirectoryUserResponse> directory(@RequestParam(required = false) String query,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        ApiPageRequests.query(query);
        return userAccountService.directory(query, ApiPageRequests.bounded(page, size, Sort.by("fullName").and(Sort.by("id"))));
    }
}
