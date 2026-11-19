package vn.cusc.meetingservice.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.response.*;
import vn.cusc.meetingservice.dto.speaking.*;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.*;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.service.MeetingSpeakingAuthorizationService.Actor;

@Service @RequiredArgsConstructor
public class MeetingSpeakingService {
    private static final List<MeetingSpeakingStatus> ACTIVE = List.of(MeetingSpeakingStatus.WAITING, MeetingSpeakingStatus.SPEAKING);
    private final MeetingRepository meetings;
    private final MeetingSpeakingRequestRepository requests;
    private final MeetingSpeakingAuthorizationService authorization;
    private final EntityManager entityManager;

    public void requireEnabledViewer(MeetingEntity meeting) { authorization.viewer(meeting, null); }

    @Transactional
    public MeetingSpeakingContextResponse context(Long meetingId, String token) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        boolean point = meeting.getCreationMode() == MeetingCreationMode.POINT;
        boolean readOnly = authorization.terminal(meeting);
        if (readOnly && point) finalizeActive(meetingId, null, "Hệ thống");
        var selected = actor.connection() == null ? null : actor.connection().getPoint();
        return new MeetingSpeakingContextResponse(point, point && actor.canModerate(), readOnly,
            point && !readOnly && selected != null, selected == null ? null : selected.getId(),
            selected == null ? null : selected.getName(), actor.displayName(),
            point ? meeting.getPoints().stream().map(p -> new MeetingPointResponse(p.getId(), p.getName())).toList() : List.of());
    }

    @Transactional
    public PageResponse<MeetingSpeakingRequestResponse> list(Long meetingId, String token, int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > 1000000) invalid("Phân trang không hợp lệ.");
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        requirePoint(meeting);
        if (authorization.terminal(meeting)) finalizeActive(meetingId, null, "Hệ thống");
        var result = requests.findByMeetingId(meetingId, PageRequest.of(page, size,
            Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))));
        return new PageResponse<>(result.getContent().stream().map(r -> response(r, actor)).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    @Transactional
    public MeetingSpeakingRequestResponse request(Long meetingId, String token, CreateSpeakingRequest input) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        requirePoint(meeting);
        authorization.requireJoined(actor);
        if (input == null) invalid("Thông tin xin phát biểu là bắt buộc.");
        String requestId = uuid(input.requestId());
        String submittedName = input.speakerName() == null || input.speakerName().isBlank() ? null : input.speakerName().trim();
        if (submittedName != null && (submittedName.length() > 255 || containsControl(submittedName))) invalid("Tên người phát biểu tối đa 255 ký tự.");
        var point = actor.connection().getPoint();
        var existing = requests.findByMeetingIdAndParticipantIdAndRequestId(meetingId, actor.participant().getId(), requestId).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getSubmittedSpeakerName(), submittedName)
                || !Objects.equals(existing.getPoint().getId(), point.getId())) {
                conflict("SPEAKING_RETRY_CONFLICT", "Mã gửi đã được dùng cho yêu cầu khác.");
            }
            return response(existing, actor);
        }
        requireLive(meeting);
        String speaker = submittedName == null ? actor.displayName() : submittedName;
        if (speaker == null || speaker.isBlank() || speaker.length() > 255 || containsControl(speaker)) invalid("Tên người phát biểu không hợp lệ.");
        String key = speaker.toLowerCase(Locale.ROOT);
        if (requests.existsByMeetingIdAndParticipantIdAndPointIdAndSpeakerKeyAndStatusIn(meetingId,
            actor.participant().getId(), point.getId(), key, ACTIVE)) {
            conflict("SPEAKING_ACTIVE_REQUEST_EXISTS", "Người này đã có yêu cầu đang chờ hoặc đang phát biểu tại điểm cầu.");
        }
        var row = new MeetingSpeakingRequestEntity();
        row.setMeeting(meeting); row.setParticipant(actor.participant()); row.setParticipantConnection(actor.connection());
        row.setPoint(point); row.setRequestId(requestId); row.setSubmittedSpeakerName(submittedName);
        row.setSpeakerName(speaker); row.setSpeakerKey(key); row.setPointName(point.getName());
        row.setRequestedByDisplayName(actor.displayName()); row.setStatus(MeetingSpeakingStatus.WAITING);
        row.setRequestedAt(utcNow());
        requests.saveAndFlush(row); entityManager.refresh(row);
        return response(row, actor);
    }

    @Transactional public MeetingSpeakingRequestResponse approve(Long meetingId, Long id, String token, SpeakingActionRequest input) {
        return transition(meetingId, id, token, input, MeetingSpeakingStatus.SPEAKING);
    }
    @Transactional public MeetingSpeakingRequestResponse finish(Long meetingId, Long id, String token, SpeakingActionRequest input) {
        return transition(meetingId, id, token, input, MeetingSpeakingStatus.FINISHED);
    }
    @Transactional public MeetingSpeakingRequestResponse cancel(Long meetingId, Long id, String token, SpeakingActionRequest input) {
        return transition(meetingId, id, token, input, MeetingSpeakingStatus.CANCELLED);
    }

    private MeetingSpeakingRequestResponse transition(Long meetingId, Long id, String token, SpeakingActionRequest input, MeetingSpeakingStatus target) {
        var meeting = meeting(meetingId, true);
        var actor = authorization.viewer(meeting, token);
        requirePoint(meeting);
        if (input == null || input.version() == null || input.version() < 0) invalid("Phiên bản yêu cầu không hợp lệ.");
        var row = requests.findByMeetingIdAndId(meetingId, id)
            .orElseThrow(() -> new MeetingSpeakingException(HttpStatus.NOT_FOUND, "SPEAKING_NOT_FOUND", "Không tìm thấy lượt phát biểu."));
        boolean permitted = actor.canModerate() || (target != MeetingSpeakingStatus.SPEAKING && own(row, actor));
        if (!permitted) throw new MeetingSpeakingException(HttpStatus.FORBIDDEN, "SPEAKING_MODERATION_FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.");
        if (input.version() > row.getVersion()) conflict("SPEAKING_VERSION_CONFLICT", "Yêu cầu đã thay đổi. Vui lòng tải lại.");
        // Exact repeated transition is a receipt, even after terminal closure; it never changes timestamps.
        if (row.getStatus() == target) return response(row, actor);
        requireLive(meeting);
        if (input.version() != row.getVersion()) conflict("SPEAKING_VERSION_CONFLICT", "Yêu cầu đã thay đổi. Vui lòng tải lại.");
        boolean valid = target == MeetingSpeakingStatus.SPEAKING && row.getStatus() == MeetingSpeakingStatus.WAITING
            || target == MeetingSpeakingStatus.CANCELLED && row.getStatus() == MeetingSpeakingStatus.WAITING
            || target == MeetingSpeakingStatus.FINISHED && row.getStatus() == MeetingSpeakingStatus.SPEAKING;
        if (!valid) conflict("SPEAKING_STATE_CONFLICT", "Không thể thực hiện thao tác ở trạng thái hiện tại.");
        LocalDateTime now = utcNow();
        if (target == MeetingSpeakingStatus.SPEAKING) {
            row.setApprovedAt(now); row.setApprovedByDisplayName(actor.displayName());
        } else {
            row.setFinishedAt(now); row.setFinishedByDisplayName(actor.displayName());
        }
        row.setStatus(target); row.setVersion(row.getVersion() + 1);
        requests.saveAndFlush(row); entityManager.refresh(row);
        return response(row, actor);
    }

    /** Caller must hold the meeting write lock; normal lifecycle transitions already do. */
    @Transactional
    public void finalizeForTerminalTransition(Long meetingId) { finalizeActive(meetingId, null, "Hệ thống"); }
    @Transactional
    public void finalizeIfTerminal(Long meetingId) {
        var meeting = meetings.findByIdForUpdate(meetingId).orElse(null);
        if (meeting != null && authorization.terminal(meeting)) finalizeActive(meetingId, null, "Hệ thống");
    }
    @Transactional
    public void finalizeForParticipantRemoval(Long meetingId, Long participantId) { finalizeActive(meetingId, participantId, "Hệ thống"); }
    private void finalizeActive(Long meetingId, Long participantId, String finisher) {
        var rows = participantId == null ? requests.findByMeetingIdAndStatusIn(meetingId, ACTIVE)
            : requests.findByMeetingIdAndParticipantIdAndStatusIn(meetingId, participantId, ACTIVE);
        LocalDateTime now = utcNow();
        for (var row : rows) {
            row.setStatus(row.getStatus() == MeetingSpeakingStatus.SPEAKING ? MeetingSpeakingStatus.FINISHED : MeetingSpeakingStatus.CANCELLED);
            row.setFinishedAt(now); row.setFinishedByDisplayName(finisher); row.setVersion(row.getVersion() + 1);
        }
        if (!rows.isEmpty()) requests.saveAllAndFlush(rows);
    }

    private MeetingSpeakingRequestResponse response(MeetingSpeakingRequestEntity row, Actor actor) {
        boolean own = own(row, actor), live = !authorization.terminal(row.getMeeting());
        return new MeetingSpeakingRequestResponse(row.getId(), row.getRequestId(), row.getVersion(), row.getPoint().getId(),
            row.getPointName(), row.getSpeakerName(), row.getRequestedByDisplayName(), row.getApprovedByDisplayName(),
            row.getFinishedByDisplayName(), row.getStatus(), instant(row.getRequestedAt()), instant(row.getApprovedAt()), instant(row.getFinishedAt()), own,
            live && actor.canModerate() && row.getStatus() == MeetingSpeakingStatus.WAITING,
            live && (actor.canModerate() || own) && row.getStatus() == MeetingSpeakingStatus.SPEAKING,
            live && (actor.canModerate() || own) && row.getStatus() == MeetingSpeakingStatus.WAITING);
    }
    private boolean own(MeetingSpeakingRequestEntity row, Actor actor) {
        return actor.participant() != null && Objects.equals(row.getParticipant().getId(), actor.participant().getId())
            && (actor.participant().getUserAccount() != null || actor.connection() != null);
    }
    private MeetingEntity meeting(Long id, boolean lock) {
        return (lock ? meetings.findByIdForUpdate(id) : meetings.findById(id)).orElseThrow(() -> new MeetingNotFoundException(id));
    }
    private void requirePoint(MeetingEntity meeting) {
        if (meeting.getCreationMode() != MeetingCreationMode.POINT) invalid("Chức năng phát biểu chỉ dùng cho cuộc họp điểm cầu.");
    }
    private void requireLive(MeetingEntity meeting) {
        if (authorization.terminal(meeting)) conflict("SPEAKING_READ_ONLY", "Cuộc họp đã kết thúc. Lịch sử phát biểu chỉ cho xem.");
    }
    private static String uuid(String value) {
        try { if (value != null && UUID.fromString(value).toString().equals(value)) return value; } catch (IllegalArgumentException ignored) { }
        invalid("Mã gửi yêu cầu phải là UUID hợp lệ."); return null;
    }
    private static boolean containsControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    private static LocalDateTime utcNow() { return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static void invalid(String message) { throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "SPEAKING_INVALID_REQUEST", message); }
    private static void conflict(String code, String message) { throw new MeetingSpeakingException(HttpStatus.CONFLICT, code, message); }
}
