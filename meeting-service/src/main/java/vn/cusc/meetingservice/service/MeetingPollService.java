package vn.cusc.meetingservice.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vn.cusc.meetingservice.dto.poll.MeetingPollContextResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollRequest;
import vn.cusc.meetingservice.dto.poll.MeetingPollResponse;
import vn.cusc.meetingservice.dto.poll.MeetingPollVoteRequest;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.MeetingPollEligibleVoterEntity;
import vn.cusc.meetingservice.entity.MeetingPollEntity;
import vn.cusc.meetingservice.entity.MeetingPollOptionEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotSelectionEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.MeetingPollConflictException;
import vn.cusc.meetingservice.exception.MeetingPollNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingPollEligibleVoterRepository;
import vn.cusc.meetingservice.repository.MeetingPollOptionRepository;
import vn.cusc.meetingservice.repository.MeetingPollRepository;
import vn.cusc.meetingservice.repository.MeetingPollBallotRepository;
import vn.cusc.meetingservice.repository.MeetingPollBallotSelectionRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.MeetingParticipantAccess.Actor;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@Service
@RequiredArgsConstructor
public class MeetingPollService {
    private final MeetingRepository meetings;
    private final MeetingPollRepository polls;
    private final MeetingParticipantRepository participants;
    private final MeetingPollOptionRepository options;
    private final MeetingPollEligibleVoterRepository eligibleVoters;
    private final MeetingPollBallotRepository ballots;
    private final MeetingPollBallotSelectionRepository selections;
    private final UserAccountRepository accounts;
    private final MeetingPollAuthorizationService authorization;
    private final MeetingStatusResolver statusResolver;
    private final EntityManager entityManager;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollContextResponse context(Long meetingId) {
        return context(meetingId, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollContextResponse context(Long meetingId, String token) {
        MeetingEntity meeting = meeting(meetingId, false);
        Actor actor = authorization.requireViewer(meeting, token);
        boolean manage = actor.canManage(meeting);
        List<MeetingPollContextResponse.Candidate> candidates = manage
            ? participants.findByMeetingId(meetingId).stream().filter(authorization::canBeEligible)
                .map(p -> new MeetingPollContextResponse.Candidate(p.getParticipantKey(), p.getFullName(), p.getRole())).toList()
            : List.of();
        return new MeetingPollContextResponse(manage, statusResolver.resolve(meeting), candidates);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MeetingPollResponse> list(Long meetingId, int page, int size) {
        return list(meetingId, page, size, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MeetingPollResponse> list(Long meetingId, int page, int size, String token) {
        if (page < 0 || size < 1 || size > 100) invalid("Phân trang không hợp lệ.");
        MeetingEntity meeting = meeting(meetingId, false);
        Actor actor = authorization.requireViewer(meeting, token);
        boolean manage = actor.canManage(meeting);
        var pageable = PageRequest.of(page, size);
        var result = manage ? polls.findByMeetingIdOrderByCreatedAtDescIdDesc(meetingId, pageable)
            : polls.findByMeetingIdAndStatusNotOrderByCreatedAtDescIdDesc(meetingId, MeetingPollStatus.DRAFT, pageable);
        return new PageResponse<>(result.getContent().stream().map(p -> response(p, actor)).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
            result.isFirst(), result.isLast());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollResponse get(Long meetingId, Long pollId) {
        return get(meetingId, pollId, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollResponse get(Long meetingId, Long pollId, String token) {
        MeetingEntity meeting = meeting(meetingId, false);
        Actor actor = authorization.requireViewer(meeting, token);
        boolean manage = actor.canManage(meeting);
        MeetingPollEntity poll = poll(meetingId, pollId, false);
        if (poll.getStatus() == MeetingPollStatus.DRAFT && !manage) {
            throw new MeetingAccessDeniedException("POLL_DRAFT_FORBIDDEN");
        }
        return response(poll, actor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollResponse active(Long meetingId) {
        return active(meetingId, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingPollResponse active(Long meetingId, String token) {
        MeetingEntity meeting = meeting(meetingId, false);
        Actor actor = authorization.requireViewer(meeting, token);
        return polls.findOpenByMeetingId(meetingId)
            .map(p -> response(p, actor)).orElse(null);
    }

    @Transactional
    public MeetingPollResponse create(Long meetingId, MeetingPollRequest request) {
        return create(meetingId, request, null);
    }

    @Transactional
    public MeetingPollResponse create(Long meetingId, MeetingPollRequest request, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireManager(meeting, token);
        requireEditable(meeting);
        MeetingPollEntity poll = new MeetingPollEntity();
        poll.setMeeting(meeting);
        if (actor.principal() != null) {
            poll.setCreatedByAccount(accounts.findById(actor.principal().accountId())
                .orElseThrow(() -> new MeetingAccessDeniedException("ACCOUNT_NOT_FOUND")));
        }
        poll.setCreatedByParticipant(actor.participant());
        configure(poll, request);
        return response(poll, actor);
    }

    @Transactional
    public MeetingPollResponse update(Long meetingId, Long pollId, MeetingPollRequest request) {
        return update(meetingId, pollId, request, null);
    }

    @Transactional
    public MeetingPollResponse update(Long meetingId, Long pollId, MeetingPollRequest request, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireManager(meeting, token);
        requireEditable(meeting);
        MeetingPollEntity poll = poll(meetingId, pollId, true);
        if (poll.getStatus() != MeetingPollStatus.DRAFT) conflict("Chỉ được sửa biểu quyết nháp.");
        configure(poll, request);
        return response(poll, actor);
    }

    @Transactional
    public MeetingPollResponse open(Long meetingId, Long pollId) {
        return open(meetingId, pollId, null);
    }

    @Transactional
    public MeetingPollResponse open(Long meetingId, Long pollId, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireManager(meeting, token);
        if (statusResolver.resolve(meeting) != MeetingStatus.IN_PROGRESS) conflict("Chỉ mở biểu quyết khi cuộc họp đang diễn ra.");
        MeetingPollEntity poll = poll(meetingId, pollId, true);
        if (poll.getStatus() == MeetingPollStatus.OPEN) return response(poll, actor);
        if (poll.getStatus() != MeetingPollStatus.DRAFT) conflict("Biểu quyết không còn ở trạng thái nháp.");
        if (polls.findOpenByMeetingId(meetingId).isPresent()) conflict("Cuộc họp đã có biểu quyết đang mở.");
        LocalDateTime now = LocalDateTime.now();
        var roster = eligibleVoters.findByPollIdOrderByIdAsc(pollId);
        eligibleVoters.deleteAll(roster.stream()
            .filter(row -> !authorization.canBeEligible(row.getParticipant())).toList());
        eligibleVoters.flush();
        for (var participant : participants.findByMeetingId(meetingId)) {
            if (authorization.canBeEligible(participant)) snapshotVoter(poll, participant, now);
        }
        poll.setStatus(MeetingPollStatus.OPEN);
        poll.setOpenedAt(now);
        polls.saveAndFlush(poll);
        return response(poll, actor);
    }

    @Transactional
    public MeetingPollResponse close(Long meetingId, Long pollId) {
        return close(meetingId, pollId, null);
    }

    @Transactional
    public MeetingPollResponse close(Long meetingId, Long pollId, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireManager(meeting, token);
        MeetingPollEntity poll = poll(meetingId, pollId, true);
        if (poll.getStatus() == MeetingPollStatus.CLOSED) return response(poll, actor);
        requireEditable(meeting);
        if (poll.getStatus() != MeetingPollStatus.OPEN) conflict("Chỉ đóng biểu quyết đang mở.");
        poll.setStatus(MeetingPollStatus.CLOSED);
        poll.setClosedAt(LocalDateTime.now());
        polls.saveAndFlush(poll);
        return response(poll, actor);
    }

    @Transactional
    public MeetingPollResponse cancel(Long meetingId, Long pollId) {
        return cancel(meetingId, pollId, null);
    }

    @Transactional
    public MeetingPollResponse cancel(Long meetingId, Long pollId, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireManager(meeting, token);
        MeetingPollEntity poll = poll(meetingId, pollId, true);
        if (poll.getStatus() == MeetingPollStatus.CANCELLED) return response(poll, actor);
        requireEditable(meeting);
        if (poll.getStatus() != MeetingPollStatus.DRAFT && poll.getStatus() != MeetingPollStatus.OPEN) conflict("Không thể hủy biểu quyết đã đóng.");
        poll.setStatus(MeetingPollStatus.CANCELLED);
        poll.setCancelledAt(LocalDateTime.now());
        polls.saveAndFlush(poll);
        return response(poll, actor);
    }

    @Transactional
    public MeetingPollResponse vote(Long meetingId, Long pollId, MeetingPollVoteRequest request) {
        return vote(meetingId, pollId, request, null);
    }

    @Transactional
    public MeetingPollResponse vote(Long meetingId, Long pollId, MeetingPollVoteRequest request, String token) {
        MeetingEntity meeting = meeting(meetingId, true);
        Actor actor = authorization.requireViewer(meeting, token);
        MeetingPollEntity poll = poll(meetingId, pollId, true);
        MeetingParticipantEntity voter = actor.participant();
        if (!authorization.active(voter)) {
            throw new MeetingAccessDeniedException("POLL_PARTICIPANT_REQUIRED");
        }
        List<Long> ids = request == null ? null : request.optionIds();
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(ids).size() != ids.size()) invalid("Phiếu biểu quyết không hợp lệ.");
        var selected = options.findByPollIdOrderBySortOrderAscIdAsc(pollId).stream()
            .filter(o -> ids.contains(o.getId())).toList();
        if (selected.size() != ids.size() || ids.size() < poll.getMinSelections()
            || ids.size() > poll.getMaxSelections()) invalid("Lựa chọn không thuộc biểu quyết hoặc vượt số lượng cho phép.");
        MeetingPollBallotEntity ballot = ballots.findByPollIdAndVoterId(pollId, voter.getId()).orElse(null);
        if (ballot != null) {
            Set<Long> previous = selections.findByBallotIdOrderByOptionSortOrderAscOptionIdAsc(ballot.getId())
                .stream().map(s -> s.getOption().getId()).collect(Collectors.toSet());
            // Same logical retry restores a committed vote even if the Poll closed meanwhile.
            if (previous.equals(new HashSet<>(ids))) return response(poll, actor);
        }
        if (statusResolver.resolve(meeting) != MeetingStatus.IN_PROGRESS || poll.getStatus() != MeetingPollStatus.OPEN) {
            conflict("Biểu quyết đã đóng hoặc cuộc họp không còn diễn ra.");
        }
        if (ballot != null && !poll.isAllowChangeVote()) conflict("Biểu quyết này không cho phép thay đổi phiếu.");
        snapshotVoter(poll, voter, LocalDateTime.now());
        if (ballot == null) {
            ballot = new MeetingPollBallotEntity();
            ballot.setPoll(poll);
            ballot.setVoter(voter);
            ballot.setSubmittedAt(LocalDateTime.now());
        } else {
            selections.deleteAll(selections.findByBallotIdOrderByOptionSortOrderAscOptionIdAsc(ballot.getId()));
            selections.flush();
            ballot.setUpdatedAt(LocalDateTime.now());
        }
        ballots.saveAndFlush(ballot);
        // Return persisted timestamp precision so replay/reconnect metadata is stable.
        entityManager.refresh(ballot);
        List<MeetingPollBallotSelectionEntity> choices = new ArrayList<>();
        for (var option : selected) {
            var choice = new MeetingPollBallotSelectionEntity();
            choice.setPoll(poll);
            choice.setBallot(ballot);
            choice.setOption(option);
            choices.add(choice);
        }
        selections.saveAllAndFlush(choices);
        return response(poll, actor);
    }

    private void configure(MeetingPollEntity poll, MeetingPollRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()
            || request.question().length() > 1000 || request.type() == null || request.resultVisibility() == null
            || (request.description() != null && request.description().length() > 20000)) invalid("Cấu hình biểu quyết không hợp lệ.");
        List<MeetingPollRequest.OptionInput> inputs = request.type() == MeetingPollType.YES_NO_ABSTAIN
            ? List.of(new MeetingPollRequest.OptionInput("Tán thành", MeetingPollOptionCode.YES),
                new MeetingPollRequest.OptionInput("Không tán thành", MeetingPollOptionCode.NO),
                new MeetingPollRequest.OptionInput("Không ý kiến", MeetingPollOptionCode.ABSTAIN))
            : request.options();
        if (inputs == null || inputs.size() < 2 || inputs.size() > 20) invalid("Cần từ 2 đến 20 phương án.");
        Set<String> labels = new HashSet<>();
        for (var input : inputs) {
            if (input == null || input.label() == null || input.label().isBlank() || input.label().length() > 255
                || !labels.add(input.label().strip().toLowerCase(Locale.ROOT))
                || (request.type() != MeetingPollType.YES_NO_ABSTAIN && input.code() != null)) invalid("Phương án không hợp lệ hoặc bị trùng.");
        }
        if (request.minSelections() < 1 || request.maxSelections() < request.minSelections()
            || request.maxSelections() > inputs.size()
            || (request.type() != MeetingPollType.MULTIPLE_CHOICE
                && (request.minSelections() != 1 || request.maxSelections() != 1))) invalid("Số lựa chọn không hợp lệ.");
        List<String> keys = request.eligibleParticipantKeys() == null ? List.of() : request.eligibleParticipantKeys();
        if (keys.size() > 1000 || keys.stream().anyMatch(k -> k == null || k.isBlank())
            || new HashSet<>(keys).size() != keys.size()) invalid("Danh sách người biểu quyết không hợp lệ.");
        Map<String, MeetingParticipantEntity> byKey = participants.findByMeetingId(poll.getMeeting().getId()).stream()
            .filter(authorization::canBeEligible).collect(Collectors.toMap(MeetingParticipantEntity::getParticipantKey, Function.identity()));
        if (!byKey.keySet().containsAll(keys)) invalid("Chỉ được chọn thành viên hợp lệ trong cuộc họp.");

        poll.setQuestion(request.question().strip());
        poll.setDescription(request.description());
        poll.setType(request.type());
        poll.setAllowChangeVote(request.allowChangeVote());
        poll.setResultVisibility(request.resultVisibility());
        poll.setMinSelections(request.minSelections());
        poll.setMaxSelections(request.maxSelections());
        polls.saveAndFlush(poll);
        options.deleteAll(options.findByPollIdOrderBySortOrderAscIdAsc(poll.getId()));
        eligibleVoters.deleteAll(eligibleVoters.findByPollIdOrderByIdAsc(poll.getId()));
        options.flush();
        eligibleVoters.flush();
        List<MeetingPollOptionEntity> newOptions = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            var option = new MeetingPollOptionEntity();
            option.setPoll(poll);
            option.setLabel(input.label().strip());
            option.setCode(input.code());
            option.setSortOrder(index);
            newOptions.add(option);
        }
        options.saveAllAndFlush(newOptions);
        eligibleVoters.saveAllAndFlush(byKey.values().stream().map(participant -> {
            var row = new MeetingPollEligibleVoterEntity();
            row.setPoll(poll);
            row.setMeeting(poll.getMeeting());
            row.setParticipant(participant);
            return row;
        }).toList());
    }

    private void snapshotVoter(MeetingPollEntity poll, MeetingParticipantEntity participant, LocalDateTime now) {
        var row = eligibleVoters.findByPollIdAndParticipantId(poll.getId(), participant.getId()).orElseGet(() -> {
                var voter = new MeetingPollEligibleVoterEntity();
                voter.setPoll(poll);
                voter.setMeeting(poll.getMeeting());
                voter.setParticipant(participant);
                return voter;
            });
        if (row.getSnapshotAt() != null) return;
        row.setParticipantKeySnapshot(participant.getParticipantKey());
        row.setFullNameSnapshot(participant.getFullName());
        row.setRoleSnapshot(participant.getRole());
        row.setSnapshotAt(now);
        eligibleVoters.saveAndFlush(row);
    }

    private MeetingPollResponse response(MeetingPollEntity poll, Actor actor) {
        boolean manage = actor.canManage(poll.getMeeting());
        var roster = eligibleVoters.findByPollIdOrderByIdAsc(poll.getId());
        MeetingParticipantEntity participant = actor.participant();
        MeetingPollBallotEntity ballot = participant == null ? null
            : ballots.findByPollIdAndVoterId(poll.getId(), participant.getId()).orElse(null);
        var myVote = ballot == null ? null : new MeetingPollResponse.MyVote(
            selections.findByBallotIdOrderByOptionSortOrderAscOptionIdAsc(ballot.getId())
                .stream().map(s -> s.getOption().getId()).toList(), ballot.getSubmittedAt(), ballot.getUpdatedAt());
        boolean canVote = poll.getStatus() == MeetingPollStatus.OPEN
            && statusResolver.resolve(poll.getMeeting()) == MeetingStatus.IN_PROGRESS
            && authorization.active(participant)
            && (ballot == null || poll.isAllowChangeVote());
        boolean seeResults = (poll.getStatus() == MeetingPollStatus.OPEN || poll.getStatus() == MeetingPollStatus.CLOSED)
            && (poll.getResultVisibility() == MeetingPollResultVisibility.LIVE
                || (poll.getResultVisibility() == MeetingPollResultVisibility.AFTER_CLOSE && poll.getStatus() == MeetingPollStatus.CLOSED)
                || (poll.getResultVisibility() == MeetingPollResultVisibility.AUTHORIZED_ONLY && manage));
        long voted = ballots.countByPollId(poll.getId());
        var pollOptions = options.findByPollIdOrderBySortOrderAscIdAsc(poll.getId());
        List<MeetingPollResponse.OptionResult> results = null;
        if (seeResults) {
            Map<Long, Long> counts = selections.countOptionsByPollId(poll.getId()).stream()
                .collect(Collectors.toMap(s -> s.getOptionId(), s -> s.getVoteCount()));
            results = pollOptions.stream().map(o -> {
                long count = counts.getOrDefault(o.getId(), 0L);
                return new MeetingPollResponse.OptionResult(o.getId(), count,
                    voted == 0 ? 0 : Math.round(count * 10000.0 / voted) / 100.0);
            }).toList();
        }
        return new MeetingPollResponse(poll.getId(), poll.getMeeting().getId(), poll.getQuestion(), poll.getDescription(),
            poll.getType(), poll.getStatus(), poll.isAllowChangeVote(), poll.getResultVisibility(),
            poll.getMinSelections(), poll.getMaxSelections(), poll.getCreatedAt(), poll.getOpenedAt(),
            poll.getClosedAt(), poll.getCancelledAt(), manage,
            pollOptions.stream()
                .map(o -> new MeetingPollResponse.OptionResponse(o.getId(), o.getLabel(), o.getCode(), o.getSortOrder())).toList(),
            manage ? roster.stream().map(r -> r.getSnapshotAt() == null ? r.getParticipant().getParticipantKey()
                : r.getParticipantKeySnapshot()).toList() : List.of(),
            roster.size(), voted, canVote, seeResults, myVote, results);
    }

    private MeetingEntity meeting(Long id, boolean lock) {
        return (lock ? meetings.findByIdForUpdate(id) : meetings.findById(id))
            .orElseThrow(() -> new MeetingNotFoundException(id));
    }

    private MeetingPollEntity poll(Long meetingId, Long pollId, boolean lock) {
        return (lock ? polls.findByIdAndMeetingIdForUpdate(pollId, meetingId) : polls.findByIdAndMeetingId(pollId, meetingId))
            .orElseThrow(() -> new MeetingPollNotFoundException(pollId));
    }

    private void requireEditable(MeetingEntity meeting) {
        MeetingStatus status = statusResolver.resolve(meeting);
        if (status == MeetingStatus.COMPLETED || status == MeetingStatus.CANCELLED) conflict("Cuộc họp đã kết thúc hoặc bị hủy; biểu quyết chỉ được xem.");
    }

    private static void invalid(String message) { throw new BusinessValidationException(message); }
    private static void conflict(String message) { throw new MeetingPollConflictException(message); }
}
