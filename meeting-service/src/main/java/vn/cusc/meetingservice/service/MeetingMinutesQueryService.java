package vn.cusc.meetingservice.service;

import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import vn.cusc.meetingservice.dto.response.MinutesListItemResponse;
import vn.cusc.meetingservice.dto.response.MinutesSummaryResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MinutesStatus;

public interface MeetingMinutesQueryService {
    PageResponse<MinutesListItemResponse> getMinutes(
        String query,
        MinutesStatus status,
        MeetingRole role,
        LocalDate from,
        LocalDate to,
        Pageable pageable
    );

    MinutesSummaryResponse getSummary();
}
