package vn.cusc.meetingservice.dto.poll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollType;

public record MeetingPollRequest(
    @NotBlank @Size(max = 1000) String question,
    @Size(max = 20000) String description,
    @NotNull MeetingPollType type,
    boolean allowChangeVote,
    @NotNull MeetingPollResultVisibility resultVisibility,
    @Min(1) @Max(20) int minSelections,
    @Min(1) @Max(20) int maxSelections,
    @Valid @Size(max = 20) List<OptionInput> options,
    @Size(max = 1000) List<@NotBlank String> eligibleParticipantKeys
) {
    public record OptionInput(@NotBlank @Size(max = 255) String label, MeetingPollOptionCode code) {}
}
