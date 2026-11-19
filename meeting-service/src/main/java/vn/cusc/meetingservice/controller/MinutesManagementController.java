package vn.cusc.meetingservice.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.dto.response.MinutesListItemResponse;
import vn.cusc.meetingservice.dto.response.MinutesSummaryResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.service.MeetingMinutesQueryService;

@Validated
@RestController
@RequestMapping("/api/minutes")
public class MinutesManagementController {

    private final MeetingMinutesQueryService queryService;

    public MinutesManagementController(MeetingMinutesQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<MinutesListItemResponse> getMinutes(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) MinutesStatus status,
        @RequestParam(required = false) MeetingRole role,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.getMinutes(
            q,
            status,
            role,
            from,
            to,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"))
        );
    }

    @GetMapping("/summary")
    public MinutesSummaryResponse getSummary() {
        return queryService.getSummary();
    }
}
