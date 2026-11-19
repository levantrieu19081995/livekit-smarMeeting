package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.exception.ConclusionException;

@Service @RequiredArgsConstructor
public class ConclusionContextBuilder {
    private final ObjectMapper json;
    private final MeetingPollRepository polls;
    private final MeetingPollOptionRepository options;
    private final MeetingPollEligibleVoterRepository voters;
    private final MeetingPollBallotRepository ballots;
    private final MeetingPollBallotSelectionRepository selections;
    private final MeetingQaQuestionRepository questions;
    private final MeetingChatMessageRepository chats;

    public ObjectNode build(MeetingEntity meeting, MeetingMinutesEntity minutes, String manual) {
        ObjectNode context = json.createObjectNode();
        var info = context.putObject("meeting");
        info.put("title", meeting.getTitle());
        info.put("description", meeting.getDescription());
        info.put("startTime", meeting.getStartTime() == null ? null : meeting.getStartTime().toString());
        info.put("endTime", meeting.getEndTime() == null ? null : meeting.getEndTime().toString());
        var participants = info.putArray("participants");
        meeting.getParticipants().stream().filter(p -> p.getStatus() != ParticipantStatus.REMOVED)
            .forEach(p -> participants.addObject().put("name", p.getFullName()).put("role", p.getRole().name()));
        try {
            context.putObject("minutes").put("sourceRef", "minutes.v" + minutes.getVersion())
                .put("content", ConclusionText.main(json.readTree(minutes.getMinutesJson())));
        } catch (Exception error) { throw invalid("Không thể đọc nội dung biên bản."); }
        context.putObject("manual").put("sourceRef", "manual.pre-ai").put("content", manual);
        var pollRows = polls.findByMeetingIdOrderByCreatedAtDescIdDesc(meeting.getId(), PageRequest.of(0, 201));
        if (pollRows.getTotalElements() > 200) throw invalid("Quá nhiều biểu quyết để tổng hợp một lần.");
        var pollData = context.putArray("polls");
        for (var poll : pollRows) {
            if (poll.getStatus() != MeetingPollStatus.CLOSED) continue;
            var result = pollData.addObject().put("sourceRef", "poll." + poll.getId()).put("question", poll.getQuestion())
                .put("description", poll.getDescription()).put("submitted", ballots.countByPollId(poll.getId()))
                .put("eligible", voters.countByPollIdAndSnapshotAtIsNotNull(poll.getId()));
            var counts = selections.countOptionsByPollId(poll.getId()).stream()
                .collect(Collectors.toMap(MeetingPollBallotSelectionRepository.OptionVoteCount::getOptionId,
                    MeetingPollBallotSelectionRepository.OptionVoteCount::getVoteCount));
            var choices = result.putArray("options");
            options.findByPollIdOrderBySortOrderAscIdAsc(poll.getId()).forEach(option ->
                choices.addObject().put("label", option.getLabel()).put("votes", counts.getOrDefault(option.getId(), 0L)));
        }
        var qaRows = questions.findByMeetingIdAndStatusInOrderByCreatedAtAscIdAsc(meeting.getId(),
            List.of(MeetingQaStatus.OPEN, MeetingQaStatus.ANSWERED), PageRequest.of(0, 1001));
        if (qaRows.getTotalElements() > 1000) throw invalid("Quá nhiều câu hỏi để tổng hợp một lần.");
        var qa = context.putArray("qa");
        qaRows.forEach(question -> qa.addObject().put("sourceRef", "qa." + question.getId())
            .put("question", question.getContent()).put("answer", question.getAnswer())
            .put("author", question.isAnonymous() ? "Ẩn danh" : (question.getAuthorDisplayName() == null ? "Người tham gia" : question.getAuthorDisplayName())));
        var messages = chats.findByMeetingIdOrderBySentAtAscIdAsc(meeting.getId(), PageRequest.of(0, 1001));
        if (messages.getTotalElements() > 1000) throw invalid("Quá nhiều ý kiến trao đổi để tổng hợp một lần.");
        var opinions = context.putArray("opinions");
        messages.forEach(message -> opinions.addObject().put("sourceRef", "chat." + message.getId())
            .put("author", message.getSenderDisplayName()).put("content", message.getContent()));
        if (context.toString().length() > 140000) throw invalid("Nội dung vượt giới hạn tổng hợp AI. Nội dung biên bản vẫn được giữ nguyên.");
        return context;
    }
    private ConclusionException invalid(String message) {
        return new ConclusionException(HttpStatus.BAD_REQUEST, "CONCLUSION_CONTEXT_INVALID", message);
    }
}
