package vn.cusc.meetingservice.dto.stt.callback;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SttProgressCallbackRequest(
    @NotNull Long meetingId,

    @NotNull
    @PositiveOrZero
    Integer processedChunks,

    @NotNull
    @PositiveOrZero
    Integer totalChunks,

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    Double progressPercent
) {
}
