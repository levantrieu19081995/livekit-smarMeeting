package vn.cusc.meetingservice.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;

public record MinutesVersionResponse(
    Long id,
    Long meetingId,
    Integer version,
    MinutesStatus status,
    MinutesVersionSource source,
    JsonNode content,
    AccountSummaryResponse createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime finalizedAt,
    Long revision
) {
    public MinutesVersionResponse(Long id, Long meetingId, Integer version, MinutesStatus status,
        MinutesVersionSource source, JsonNode content, AccountSummaryResponse createdBy,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime finalizedAt) {
        this(id, meetingId, version, status, source, content, createdBy, createdAt, updatedAt, finalizedAt, 0L);
    }
}
