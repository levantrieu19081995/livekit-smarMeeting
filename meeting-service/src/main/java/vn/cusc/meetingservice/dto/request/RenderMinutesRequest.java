package vn.cusc.meetingservice.dto.request;

import jakarta.validation.constraints.NotNull;
import vn.cusc.meetingservice.model.enums.MinutesTemplateCode;

public record RenderMinutesRequest(
    @NotNull MinutesTemplateCode templateCode
) {
}
