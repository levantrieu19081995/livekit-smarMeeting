package vn.cusc.meetingservice.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.qa.MeetingQaContextResponse;
import vn.cusc.meetingservice.dto.qa.MeetingQaActionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaQuestionRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaQuestionResponse;
import vn.cusc.meetingservice.dto.qa.MeetingQaSettingsRequest;
import vn.cusc.meetingservice.dto.qa.MeetingQaUpvoteRequest;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingQaQuestionEntity;
import vn.cusc.meetingservice.entity.MeetingQaSettingsEntity;
import vn.cusc.meetingservice.entity.MeetingQaUpvoteEntity;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.MeetingQaException;
import vn.cusc.meetingservice.model.enums.MeetingQaFilter;
import vn.cusc.meetingservice.model.enums.MeetingQaAction;
import vn.cusc.meetingservice.model.enums.MeetingQaSort;
import vn.cusc.meetingservice.model.enums.MeetingQaStatus;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingQaQuestionRepository;
import vn.cusc.meetingservice.repository.MeetingQaSettingsRepository;
import vn.cusc.meetingservice.repository.MeetingQaUpvoteRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.MeetingQaAuthorizationService.Actor;
import vn.cusc.meetingservice.util.MeetingStatusResolver;
import vn.cusc.meetingservice.util.MeetingQaCsv;

@Service
@RequiredArgsConstructor
public class MeetingQaService {
    private final MeetingRepository meetings;
    private final MeetingQaSettingsRepository settings;
    private final MeetingQaQuestionRepository questions;
    private final MeetingQaUpvoteRepository upvotes;
    private final MeetingQaAuthorizationService authorization;
    private final MeetingStatusResolver statusResolver;
    private final EntityManager entityManager;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingQaContextResponse context(Long meetingId, String token) {
        var meeting = meeting(meetingId, false);
        var actor = authorization.viewer(meeting, token);
        var config = configuration(meeting);
        return contextResponse(meeting, actor, config);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MeetingQaQuestionResponse> list(Long meetingId, String token, int page, int size,
        MeetingQaSort sort, MeetingQaFilter filter) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE
            || sort == null || filter == null) invalid("Phân trang hoặc sắp xếp không hợp lệ.");
        var meeting = meeting(meetingId, false);
        var actor = authorization.viewer(meeting, token);
        if (filter == MeetingQaFilter.PENDING && !actor.canManage()) forbidden("QA_MODERATION_FORBIDDEN");
        var result = questions.findAll(visibility(meetingId, actor, filter), PageRequest.of(page, size, ordering(sort)));
        var voted = votedIds(actor, result.getContent());
        return new PageResponse<>(result.getContent().stream().map(q -> response(q, actor, voted)).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    @Transactional
    public MeetingQaQuestionResponse ask(Long meetingId, String token, MeetingQaQuestionRequest request) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        authorization.requireParticipantToken(actor);
        if (request == null || request.content() == null || request.content().trim().isEmpty()
            || request.content().trim().length() > 2000) invalid("Câu hỏi cần có từ 1 đến 2000 ký tự.");
        String clientId = clientId(request.clientQuestionId());
        String content = request.content().trim();
        var existing = questions.findByMeetingIdAndParticipantIdAndClientQuestionId(meetingId,
            actor.participant().getId(), clientId).orElse(null);
        if (existing != null) {
            if (!actor.canManage() && (existing.getStatus() == MeetingQaStatus.HIDDEN
                || existing.getStatus() == MeetingQaStatus.DELETED)) throw notFound();
            if (!existing.getContent().equals(content) || existing.isAnonymous() != request.anonymous()) {
                conflict("Mã gửi câu hỏi đã được sử dụng cho nội dung khác.");
            }
            // A completed retry returns its original receipt without creating a new question,
            // even if the moderator disabled Q&A or the meeting ended in the meantime.
            return response(existing, actor, votedIds(actor, List.of(existing)));
        }
        var config = configuration(meeting);
        requireOpen(meeting, config);
        if (request.anonymous() && !config.isAnonymousAllowed()) conflict("Cuộc họp đã tắt câu hỏi ẩn danh.");
        var question = new MeetingQaQuestionEntity();
        question.setMeeting(meeting);
        question.setParticipant(actor.participant());
        question.setClientQuestionId(clientId);
        question.setContent(content);
        question.setAnonymous(request.anonymous());
        question.setAuthorDisplayName(request.anonymous() ? null : actor.participant().getFullName());
        question.setStatus(config.isModerationEnabled() ? MeetingQaStatus.PENDING : MeetingQaStatus.OPEN);
        questions.saveAndFlush(question);
        // Return the persisted timestamp precision (PostgreSQL uses microseconds),
        // so a retried receipt matches the initial response exactly.
        entityManager.refresh(question);
        return response(question, actor, Set.of());
    }

    @Transactional
    public MeetingQaQuestionResponse upvote(Long meetingId, Long questionId, String token, MeetingQaUpvoteRequest request) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        authorization.requireParticipantToken(actor);
        requireOpen(meeting, configuration(meeting));
        if (request == null || request.upvoted() == null) invalid("Cần xác định trạng thái bình chọn.");
        var question = questions.findByMeetingIdAndId(meetingId, questionId)
            .orElseThrow(() -> notFound());
        // Do not reveal whether a pending/hidden/deleted question belongs to somebody else.
        if (question.getStatus() != MeetingQaStatus.OPEN && question.getStatus() != MeetingQaStatus.ANSWERED) {
            throw notFound();
        }
        var existing = upvotes.findByQuestionIdAndParticipantId(questionId, actor.participant().getId()).orElse(null);
        if (request.upvoted() && existing == null) {
            var vote = new MeetingQaUpvoteEntity();
            vote.setMeeting(meeting);
            vote.setQuestion(question);
            vote.setParticipant(actor.participant());
            upvotes.save(vote);
            question.setUpvoteCount(question.getUpvoteCount() + 1);
            question.setVersion(question.getVersion() + 1);
        } else if (!request.upvoted() && existing != null) {
            upvotes.delete(existing);
            question.setUpvoteCount(question.getUpvoteCount() - 1);
            question.setVersion(question.getVersion() + 1);
        }
        questions.saveAndFlush(question);
        entityManager.refresh(question);
        return response(question, actor, request.upvoted() ? Set.of(questionId) : Set.of());
    }

    @Transactional
    public MeetingQaContextResponse updateSettings(Long meetingId, String token, MeetingQaSettingsRequest request) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        requireManager(actor);
        requireLive(meeting);
        if (request == null || request.enabled() == null || request.anonymousAllowed() == null
            || request.moderationEnabled() == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            invalid("Cần cung cấp đầy đủ thiết lập và phiên bản hỏi đáp.");
        }
        var config = configuration(meeting);
        requireVersion(config.getVersion(), request.expectedVersion());
        if (config.isEnabled() != request.enabled() || config.isAnonymousAllowed() != request.anonymousAllowed()
            || config.isModerationEnabled() != request.moderationEnabled()) {
            config.setEnabled(request.enabled());
            config.setAnonymousAllowed(request.anonymousAllowed());
            config.setModerationEnabled(request.moderationEnabled());
            config.setVersion(config.getVersion() + 1);
            settings.saveAndFlush(config);
            entityManager.refresh(config);
        }
        // Existing pending questions and anonymous snapshots are deliberately unchanged.
        return contextResponse(meeting, actor, config);
    }

    @Transactional
    public MeetingQaQuestionResponse moderate(Long meetingId, Long questionId, String token, MeetingQaActionRequest request) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        requireManager(actor);
        requireLive(meeting);
        if (request == null || request.action() == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            invalid("Cần cung cấp thao tác và phiên bản câu hỏi.");
        }
        String answer = request.answer() == null ? null : request.answer().trim();
        if (answer != null && (request.action() != MeetingQaAction.ANSWER || answer.isEmpty() || answer.length() > 5000)) {
            invalid("Câu trả lời cần có từ 1 đến 5000 ký tự và chỉ dùng cho thao tác trả lời.");
        }
        var question = questions.findByMeetingIdAndId(meetingId, questionId).orElseThrow(() -> notFound());
        requireVersion(question.getVersion(), request.expectedVersion());
        var target = switch (request.action()) {
            case APPROVE -> MeetingQaStatus.OPEN;
            case ANSWER -> MeetingQaStatus.ANSWERED;
            case HIDE -> MeetingQaStatus.HIDDEN;
            case DELETE -> MeetingQaStatus.DELETED;
        };
        boolean answerChanged = answer != null && !answer.equals(question.getAnswer());
        if (question.getStatus() != target || answerChanged) {
            boolean allowed = switch (request.action()) {
                case APPROVE -> question.getStatus() == MeetingQaStatus.PENDING;
                case ANSWER -> question.getStatus() == MeetingQaStatus.OPEN || question.getStatus() == MeetingQaStatus.ANSWERED;
                case HIDE -> question.getStatus() == MeetingQaStatus.PENDING || question.getStatus() == MeetingQaStatus.OPEN
                    || question.getStatus() == MeetingQaStatus.ANSWERED;
                case DELETE -> question.getStatus() != MeetingQaStatus.DELETED;
            };
            if (!allowed) conflict("Trạng thái câu hỏi không cho phép thao tác này.");
            question.setStatus(target);
            if (request.action() == MeetingQaAction.ANSWER) {
                question.setAnsweredAt(LocalDateTime.now());
                if (answer != null) {
                    question.setAnswer(answer);
                    question.setAnswerAuthorDisplayName(actor.displayName());
                }
            }
            question.setVersion(question.getVersion() + 1);
            questions.saveAndFlush(question);
            entityManager.refresh(question);
        }
        return response(question, actor, votedIds(actor, List.of(question)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String report(Long meetingId, String token) {
        var meeting = meeting(meetingId, false);
        requireManager(authorization.viewer(meeting, token));
        var csv = new StringBuilder("\uFEFF");
        csv.append(MeetingQaCsv.row("Nội dung câu hỏi", "Người hỏi", "Trạng thái", "Lượt ủng hộ",
            "Thời gian gửi", "Thời gian trả lời", "Nội dung trả lời", "Người trả lời"));
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        // Read bounded database batches within one snapshot; do not load the complete
        // question aggregate or include internal participant/client identifiers.
        Pageable pageable = PageRequest.of(0, 200);
        while (true) {
            var batch = questions.findReportRows(meetingId, pageable);
            for (var question : batch) {
                String status = switch (question.getStatus()) {
                    case PENDING -> "Chờ duyệt";
                    case OPEN -> "Chưa trả lời";
                    case ANSWERED -> "Đã trả lời";
                    case HIDDEN -> "Đã ẩn";
                    case DELETED -> "Đã xóa";
                };
                csv.append(MeetingQaCsv.row(question.getContent(), question.getAnonymous() ? "Ẩn danh" : question.getAuthorDisplayName(),
                    status, Long.toString(question.getUpvoteCount()), question.getCreatedAt().format(dateFormat),
                    question.getAnsweredAt() == null ? "" : question.getAnsweredAt().format(dateFormat),
                    question.getAnswer(), question.getAnswerAuthorDisplayName()));
            }
            if (!batch.hasNext()) break;
            pageable = batch.nextPageable();
        }
        return csv.toString();
    }

    private MeetingEntity meeting(Long id, boolean lock) {
        return (lock ? meetings.findByIdForUpdate(id) : meetings.findById(id))
            .orElseThrow(() -> new MeetingNotFoundException(id));
    }

    private MeetingQaSettingsEntity configuration(MeetingEntity meeting) {
        return settings.findByMeetingId(meeting.getId()).orElseGet(() -> {
            var defaults = new MeetingQaSettingsEntity();
            defaults.setMeeting(meeting);
            return defaults;
        });
    }

    private void requireOpen(MeetingEntity meeting, MeetingQaSettingsEntity config) {
        requireLive(meeting);
        if (!config.isEnabled()) conflict("Hỏi đáp đang tắt trong cuộc họp này.");
    }

    private void requireLive(MeetingEntity meeting) {
        if (statusResolver.resolve(meeting) != MeetingStatus.IN_PROGRESS) conflict("Chỉ thao tác hỏi đáp khi cuộc họp đang diễn ra.");
    }

    private void requireManager(Actor actor) {
        if (!actor.canManage()) forbidden("QA_MODERATION_FORBIDDEN");
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) conflict("Dữ liệu hỏi đáp đã thay đổi. Hãy tải lại trước khi thao tác.");
    }

    private MeetingQaContextResponse contextResponse(MeetingEntity meeting, Actor actor, MeetingQaSettingsEntity config) {
        var status = statusResolver.resolve(meeting);
        boolean participate = config.isEnabled() && status == MeetingStatus.IN_PROGRESS && actor.hasParticipantToken();
        return new MeetingQaContextResponse(config.isEnabled(), config.isAnonymousAllowed(),
            config.isModerationEnabled(), config.getVersion(), status, actor.canManage(), participate, participate);
    }

    private Specification<MeetingQaQuestionEntity> visibility(Long meetingId, Actor actor, MeetingQaFilter filter) {
        return (root, query, cb) -> {
            var predicate = cb.equal(root.get("meeting").get("id"), meetingId);
            var own = actor.participant() == null ? cb.disjunction()
                : cb.equal(root.get("participant").get("id"), actor.participant().getId());
            if (!actor.canManage()) {
                var publicQuestion = root.get("status").in(MeetingQaStatus.OPEN, MeetingQaStatus.ANSWERED);
                var ownPending = cb.and(own, cb.equal(root.get("status"), MeetingQaStatus.PENDING));
                predicate = cb.and(predicate, cb.or(publicQuestion, ownPending));
            }
            if (filter == MeetingQaFilter.MINE) predicate = cb.and(predicate, own);
            if (filter == MeetingQaFilter.PENDING) predicate = cb.and(predicate, cb.equal(root.get("status"), MeetingQaStatus.PENDING));
            return predicate;
        };
    }

    private Sort ordering(MeetingQaSort sort) {
        return switch (sort) {
            case OLDEST -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
            case NEWEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case POPULAR -> Sort.by(Sort.Order.desc("upvoteCount"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
    }

    private Set<Long> votedIds(Actor actor, List<MeetingQaQuestionEntity> rows) {
        if (actor.participant() == null || rows.isEmpty()) return Set.of();
        return new HashSet<>(upvotes.findVotedQuestionIds(actor.participant().getId(), rows.stream().map(MeetingQaQuestionEntity::getId).toList()));
    }

    private MeetingQaQuestionResponse response(MeetingQaQuestionEntity question, Actor actor, Set<Long> voted) {
        boolean mine = actor.participant() != null && actor.participant().getId().equals(question.getParticipant().getId());
        return new MeetingQaQuestionResponse(question.getId(), mine ? question.getClientQuestionId() : null,
            question.getContent(), question.isAnonymous(), question.isAnonymous() ? "Ẩn danh" : question.getAuthorDisplayName(),
            question.getStatus(), question.getCreatedAt(), question.getUpdatedAt(), question.getAnsweredAt(),
            question.getUpvoteCount(), voted.contains(question.getId()), mine, question.getVersion(),
            question.getAnswer(), question.getAnswerAuthorDisplayName());
    }

    private String clientId(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException | NullPointerException exception) {
            invalid("Mã gửi câu hỏi phải là UUID hợp lệ.");
            return null;
        }
    }

    private static MeetingQaException notFound() { return new MeetingQaException(HttpStatus.NOT_FOUND, "QA_QUESTION_NOT_FOUND", "Không tìm thấy câu hỏi."); }
    private static void invalid(String message) { throw new MeetingQaException(HttpStatus.BAD_REQUEST, "QA_VALIDATION_ERROR", message); }
    private static void conflict(String message) { throw new MeetingQaException(HttpStatus.CONFLICT, "QA_CONFLICT", message); }
    private static void forbidden(String code) { throw new MeetingQaException(HttpStatus.FORBIDDEN, code, "Bạn không có quyền điều hành hỏi đáp."); }
}
