package vn.cusc.meetingservice.dto.response;

import vn.cusc.meetingservice.model.enums.MinutesStatus;

public record MinutesRenderResponse(
    Long meetingId,
    Integer version,
    MinutesStatus status,
    MinutesTemplateResponse template,
    String html,
    boolean canExportPdf
) {
}
