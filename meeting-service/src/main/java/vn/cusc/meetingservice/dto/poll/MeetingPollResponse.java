package vn.cusc.meetingservice.dto.poll;

import java.time.LocalDateTime;
import java.util.List;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;

public record MeetingPollResponse(
    Long id, Long meetingId, String question, String description, MeetingPollType type,
    MeetingPollStatus status, boolean allowChangeVote, MeetingPollResultVisibility resultVisibility,
    int minSelections, int maxSelections, LocalDateTime createdAt, LocalDateTime openedAt,
    LocalDateTime closedAt, LocalDateTime cancelledAt, boolean canManage,
    List<OptionResponse> options, List<String> eligibleParticipantKeys, long eligibleCount,
    long votedCount, boolean canVote, boolean canSeeResults, MyVote myVote, List<OptionResult> results
) {
    public record OptionResponse(Long id, String label, MeetingPollOptionCode code, int sortOrder) {}
    public record MyVote(List<Long> optionIds, LocalDateTime submittedAt, LocalDateTime updatedAt) {}
    public record OptionResult(Long optionId, long count, double percentage) {}
}
