package vn.cusc.meetingservice.dto.stt.callback;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;

public record SttCompletionCallbackRequest(

    @NotNull
    Long meetingId,

    @NotNull
    SttTerminalStatus status,

    @NotNull
    @PositiveOrZero
    Integer totalManifests,

    @NotNull
    @PositiveOrZero
    Integer totalSegments,

    @NotNull
    @PositiveOrZero
    Integer totalChunks,

    @NotNull
    @PositiveOrZero
    Integer processedChunks,

    @NotNull
    @PositiveOrZero
    Integer succeededChunks,

    @NotNull
    @PositiveOrZero
    Integer failedChunks,

    String errorMessage

) {
}
