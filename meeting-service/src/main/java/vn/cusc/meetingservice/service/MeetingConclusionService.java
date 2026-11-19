package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.*;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@Service @RequiredArgsConstructor
public class MeetingConclusionService {
    private final MeetingRepository meetings;
    private final MeetingMinutesRepository minutes;
    private final MeetingConclusionRepository conclusions;
    private final MeetingMinutesAuthorizationService authorization;
    private final MeetingStatusResolver statusResolver;
    private final ConclusionContextBuilder contextBuilder;
    private final ConclusionResultValidator resultValidator;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public View get(Long meetingId) {
        var meeting = authorization.requireMeetingView(meetingId);
        return view(meeting, conclusions.findByMeetingId(meetingId).orElse(null));
    }

    @Transactional
    public View generate(Long meetingId, Generate request) {
        var meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
        authorization.requireMeetingAccess(meeting);
        var existing = conclusions.findByMeetingId(meetingId).orElse(null);
        if (existing != null && "GENERATED".equals(existing.getStatus()))
            throw business("AI_CONCLUSION_ALREADY_GENERATED", "Cuộc họp đã tạo kết luận bằng AI. Bạn có thể chỉnh sửa kết luận thủ công.");
        if (existing != null && "GENERATING".equals(existing.getStatus())) return view(meeting, existing);
        if (statusResolver.resolve(meeting) != MeetingStatus.COMPLETED)
            throw business("CONCLUSION_MEETING_NOT_COMPLETED", "Hãy kết thúc cuộc họp trước khi tạo kết luận.");
        var source = minutes.findFirstByMeetingIdOrderByVersionDesc(meetingId).orElseThrow(() ->
            business("CONCLUSION_MINUTES_REQUIRED", "Vui lòng tạo biên bản trước."));
        if (source.getStatus() != MinutesStatus.DRAFT && source.getStatus() != MinutesStatus.FINAL)
            throw business("CONCLUSION_MINUTES_NOT_READY", "Biên bản chưa sẵn sàng để tổng hợp.");
        if (request == null || request.expectedMinutesVersion() == null || !source.getVersion().equals(request.expectedMinutesVersion()))
            throw business("CONCLUSION_SOURCE_CHANGED", "Biên bản đã thay đổi. Hãy tải lại trước khi tạo kết luận.");
        String manual;
        String sourceManual;
        try { sourceManual = ConclusionText.manual(json.readTree(source.getMinutesJson()));
            manual = request.manualContent() == null ? sourceManual : request.manualContent(); }
        catch (Exception error) { throw business("CONCLUSION_INVALID_CONTENT", "Không thể đọc kết luận hiện tại."); }
        if (request.expectedStoredConclusion() == null || !sourceManual.trim().equals(request.expectedStoredConclusion().trim()))
            throw business("CONCLUSION_SOURCE_CHANGED", "Kết luận đã thay đổi. Hãy tải lại trước khi gọi AI.");
        if (manual.length() > 200000) throw business("CONCLUSION_INVALID_CONTENT", "Nội dung kết luận quá dài.");
        var context = contextBuilder.build(meeting, source, manual);
        var entity = existing == null ? new MeetingConclusionEntity() : existing;
        entity.setMeeting(meeting); entity.setStatus("GENERATING"); entity.setStage("QUEUED");
        entity.setGenerationId(UUID.randomUUID().toString()); entity.setSourceMinutesVersion(source.getVersion());
        entity.setManualContentBeforeAi(manual); entity.setSourceManualContent(sourceManual); entity.setContextJson(context.toString());
        entity.setGeneratedByAccount(authorization.requireCurrentAccount());
        entity.setAvailableAt(LocalDateTime.now()); entity.setDeliveryAttempts(0);
        entity.setLeaseToken(null); entity.setLeaseUntil(null); entity.setErrorCode(null); entity.setErrorMessage(null);
        if (existing != null) entity.setRevision(entity.getRevision() + 1);
        conclusions.saveAndFlush(entity);
        return view(meeting, entity);
    }

    @Transactional(readOnly = true)
    public List<Long> due() { return conclusions.due(LocalDateTime.now(), PageRequest.of(0, 10)); }

    @Transactional
    public Optional<Work> claim(Long meetingId) {
        var meeting = meetings.findByIdForUpdate(meetingId).orElse(null);
        if (meeting == null) return Optional.empty();
        var entity = conclusions.findByMeetingId(meetingId).orElse(null);
        var now = LocalDateTime.now();
        if (entity == null || !"GENERATING".equals(entity.getStatus()) || entity.getAvailableAt().isAfter(now)
            || (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now))) return Optional.empty();
        entity.setLeaseToken(UUID.randomUUID().toString()); entity.setLeaseUntil(now.plusSeconds(300));
        entity.setStage("PROCESSING"); entity.setDeliveryAttempts(entity.getDeliveryAttempts() + 1);
        try {
            return Optional.of(new Work(meetingId, entity.getGenerationId(), entity.getLeaseToken(), json.readTree(entity.getContextJson())));
        } catch (Exception error) { throw new IllegalStateException("Invalid stored conclusion context"); }
    }

    @Transactional
    public boolean complete(Work work, ProviderResponse receipt) {
        var meeting = meetings.findByIdForUpdate(work.meetingId()).orElse(null);
        if (meeting == null) return false;
        var entity = conclusions.findByMeetingId(work.meetingId()).orElse(null);
        if (!owns(entity, work)) return false;
        if (receipt == null || !work.generationId().equals(receipt.generationId())) throw new IllegalArgumentException("Mismatched AI receipt");
        resultValidator.validate(receipt.result(), work.context());
        var latest = minutes.findFirstByMeetingIdOrderByVersionDesc(work.meetingId()).orElseThrow();
        try {
            var content = json.readTree(latest.getMinutesJson());
            ObjectNode nextContent = content.isObject() ? ((ObjectNode) content).deepCopy() : json.createObjectNode().put("mainContent", ConclusionText.main(content));
            // Edits made while AI was running have priority over the old snapshot.
            String currentManual = ConclusionText.manual(content);
            String manual = currentManual.equals(entity.getSourceManualContent()) ? entity.getManualContentBeforeAi() : currentManual;
            String aiText = ConclusionText.format(receipt.result());
            String finalContent = manual.isBlank() ? aiText : manual + "\n\nAI đề xuất:\n" + aiText;
            nextContent.put("conclusion", finalContent);
            var next = new MeetingMinutesEntity(); next.setMeeting(meeting); next.setVersion(latest.getVersion() + 1);
            next.setStatus(MinutesStatus.DRAFT); next.setSource(MinutesVersionSource.AI_GENERATED);
            String serialized = json.writeValueAsString(nextContent);
            if (serialized.length() > 1_000_000) throw new IllegalArgumentException("Minutes content too large");
            next.setMinutesJson(serialized); next.setGeneratedAt(LocalDateTime.now());
            next.setCreatedByAccount(entity.getGeneratedByAccount()); minutes.saveAndFlush(next);
            entity.setAiGeneratedContent(json.writeValueAsString(receipt.result())); entity.setFinalContent(finalContent);
            entity.setStatus("GENERATED"); entity.setStage("COMPLETED"); entity.setGeneratedAt(LocalDateTime.now());
            entity.setRevision(entity.getRevision() + 1); entity.setLeaseToken(null); entity.setLeaseUntil(null);
            entity.setErrorCode(null); entity.setErrorMessage(null); conclusions.saveAndFlush(entity);
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid persisted conclusion JSON"); }
    }

    @Transactional
    public void fail(Work work, String code, boolean retryable) {
        if (meetings.findByIdForUpdate(work.meetingId()).isEmpty()) return;
        var entity = conclusions.findByMeetingId(work.meetingId()).orElse(null);
        if (!owns(entity, work)) return;
        entity.setLeaseToken(null); entity.setLeaseUntil(null);
        if (retryable && entity.getDeliveryAttempts() < 5) {
            entity.setStage("QUEUED"); entity.setAvailableAt(LocalDateTime.now().plusSeconds(entity.getDeliveryAttempts() * 10L));
        } else {
            entity.setStatus("FAILED"); entity.setStage("FAILED"); entity.setErrorCode(code);
            entity.setErrorMessage("Không thể tạo kết luận bằng AI. Nội dung đã nhập vẫn được giữ. Bạn có thể thử lại.");
        }
    }

    private boolean owns(MeetingConclusionEntity entity, Work work) {
        return entity != null && "GENERATING".equals(entity.getStatus()) && work.generationId().equals(entity.getGenerationId())
            && work.leaseToken().equals(entity.getLeaseToken());
    }

    private View view(MeetingEntity meeting, MeetingConclusionEntity entity) {
        boolean manage;
        try { authorization.requireMeetingAccess(meeting); manage = true; }
        catch (MeetingAccessDeniedException error) { manage = false; }
        var current = minutes.findFirstByMeetingIdOrderByVersionDesc(meeting.getId()).orElse(null);
        boolean ready = current != null && (current.getStatus() == MinutesStatus.DRAFT || current.getStatus() == MinutesStatus.FINAL)
            && statusResolver.resolve(meeting) == MeetingStatus.COMPLETED;
        if (entity == null) return new View("NOT_GENERATED", null, null, null, null, null, null, null, 0,
            null, null, manage, manage && ready, current == null ? null : current.getVersion());
        Result result = null;
        if (entity.getAiGeneratedContent() != null) {
            try { result = json.readValue(entity.getAiGeneratedContent(), Result.class); }
            catch (Exception error) { throw new IllegalStateException("Invalid stored AI result"); }
        }
        return new View(entity.getStatus(), entity.getStage(), entity.getGenerationId(), entity.getSourceMinutesVersion(),
            entity.getManualContentBeforeAi(), result, entity.getFinalContent(), entity.getGeneratedAt(), entity.getRevision(),
            entity.getErrorCode(), entity.getErrorMessage(), manage,
            manage && ready && "FAILED".equals(entity.getStatus()), current == null ? null : current.getVersion());
    }

    private ConclusionException business(String code, String message) {
        return new ConclusionException(HttpStatus.CONFLICT, code, message);
    }
}
