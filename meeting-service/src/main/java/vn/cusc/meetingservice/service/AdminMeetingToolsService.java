package vn.cusc.meetingservice.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.request.AdminMeetingOverrideRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@Service
public class AdminMeetingToolsService {

    private static final Logger log = LoggerFactory.getLogger(AdminMeetingToolsService.class);

    private final MeetingRepository meetingRepository;
    private final MeetingMinutesJobRepository minutesJobRepository;
    private final MeetingMinutesPreconditionService minutesPreconditionService;
    private final MeetingStatusResolver statusResolver;
    private final CurrentUserService currentUserService;
    private final MeetingAccessPolicyService accessPolicy;
    private final EntityManager entityManager;
    private MeetingSpeakingService meetingSpeakingService;
    private MeetingCaptureService capture;
    private MeetingPollTerminalService polls;
    private MeetingMediaCleanupService media;

    @Autowired public void setTerminalServices(MeetingCaptureService capture, MeetingPollTerminalService polls, MeetingMediaCleanupService media) {
        this.capture = capture; this.polls = polls; this.media = media;
    }

    public boolean cleanupPending(Long meetingId) { return media != null && media.pending(meetingId); }

    @Autowired
    public void setMeetingSpeakingService(MeetingSpeakingService service) { this.meetingSpeakingService = service; }

    public AdminMeetingToolsService(
        MeetingRepository meetingRepository,
        MeetingMinutesJobRepository minutesJobRepository,
        MeetingMinutesPreconditionService minutesPreconditionService,
        MeetingStatusResolver statusResolver,
        CurrentUserService currentUserService,
        EntityManager entityManager,
        MeetingAccessPolicyService accessPolicy
    ) {
        this.meetingRepository = meetingRepository;
        this.minutesJobRepository = minutesJobRepository;
        this.minutesPreconditionService = minutesPreconditionService;
        this.statusResolver = statusResolver;
        this.currentUserService = currentUserService;
        this.entityManager = entityManager;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public void overrideMeeting(Long meetingId, AdminMeetingOverrideRequest request) {
        if (request.startTime() == null && request.endTime() == null && request.status() == null) {
            throw new BusinessValidationException("Khong co du lieu thay doi");
        }

        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        accessPolicy.requireCanManageMeeting(meeting);
        if (meeting.getCreationMode() == vn.cusc.meetingservice.model.enums.MeetingCreationMode.POINT && meetingSpeakingService != null)
            meetingSpeakingService.requireEnabledViewer(meeting);

        LocalDateTime oldStartTime = meeting.getStartTime();
        LocalDateTime oldEndTime = meeting.getEndTime();
        MeetingStatus oldStatus = meeting.getStatus();

        LocalDateTime newStartTime = request.startTime() != null ? request.startTime() : oldStartTime;
        LocalDateTime newEndTime = request.endTime() != null ? request.endTime() : oldEndTime;
        MeetingStatus newStatus = request.status() != null ? request.status() : oldStatus;
        if ((oldStatus == MeetingStatus.COMPLETED || oldStatus == MeetingStatus.CANCELLED)
            && (newStatus == MeetingStatus.IN_PROGRESS || newStatus == MeetingStatus.UPCOMING)
            && cleanupPending(meetingId)) {
            throw new BusinessValidationException("Phòng họp cũ đang được đóng. Vui lòng thử lại sau khi hoàn tất dọn tài nguyên.");
        }

        if ((meeting.getCreationMode() == vn.cusc.meetingservice.model.enums.MeetingCreationMode.SCHEDULED
            || meeting.getCreationMode() == vn.cusc.meetingservice.model.enums.MeetingCreationMode.POINT)
            && (newStartTime == null || newEndTime == null || !newEndTime.isAfter(newStartTime))) {
            throw new BusinessValidationException("endTime phai lon hon startTime");
        }
        if (meeting.getCreationMode() != vn.cusc.meetingservice.model.enums.MeetingCreationMode.SCHEDULED
            && meeting.getCreationMode() != vn.cusc.meetingservice.model.enums.MeetingCreationMode.POINT
            && (request.startTime() != null || request.endTime() != null)) {
            throw new BusinessValidationException("Cuộc họp tức thì/sử dụng sau không hiệu chỉnh lịch họp.");
        }
        if (newStatus == MeetingStatus.COMPLETED) {
            throw new BusinessValidationException("Su dung API ket thuc cuoc hop de chuyen sang COMPLETED");
        }
        if (isNoOp(oldStartTime, newStartTime, oldEndTime, newEndTime, oldStatus, newStatus)) {
            throw new BusinessValidationException("Khong co du lieu thay doi");
        }
        if (request.status() != null && request.status() != oldStatus && isBlank(request.reason())) {
            throw new BusinessValidationException("Ly do thay doi trang thai la bat buoc");
        }
        if (newStatus == MeetingStatus.UPCOMING || newStatus == MeetingStatus.IN_PROGRESS) {
            MeetingEntity candidate = new MeetingEntity();
            candidate.setStartTime(newStartTime);
            candidate.setEndTime(newEndTime);
            candidate.setStatus(newStatus);
            candidate.setCreationMode(meeting.getCreationMode());
            if (statusResolver.resolve(candidate) != newStatus) {
                throw new BusinessValidationException("Trang thai khong phu hop voi thoi gian cuoc hop");
            }
        }

        if (newStatus == MeetingStatus.CANCELLED) {
            if (capture != null) capture.finalizeForTerminalTransition(meetingId);
            if (polls != null) polls.closeForTerminalTransition(meetingId);
            if (meetingSpeakingService != null) meetingSpeakingService.finalizeForTerminalTransition(meetingId);
        }
        if (request.startTime() != null) {
            meeting.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            meeting.setEndTime(request.endTime());
        }
        if (request.status() != null) {
            meeting.setStatus(request.status());
        }

        meetingRepository.save(meeting);
        if (newStatus == MeetingStatus.CANCELLED && media != null)
            media.enqueue(meetingId, "DELETE_ROOM", meeting.getRoomName(), null);
        log.info(
            "Admin meeting override actorPublicId={} meetingId={} oldStartTime={} newStartTime={} oldEndTime={} newEndTime={} oldStatus={} newStatus={} reason={}",
            currentUserService.getCurrentPrincipal().map(principal -> principal.publicId()).orElse(null),
            meetingId,
            oldStartTime,
            meeting.getStartTime(),
            oldEndTime,
            meeting.getEndTime(),
            oldStatus,
            meeting.getStatus(),
            request.reason()
        );
    }

    @Transactional
    public void resetMinutesArtifacts(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        accessPolicy.requireCanManageMeeting(meeting);

        if (statusResolver.resolve(meeting) != MeetingStatus.COMPLETED) {
            throw new BusinessValidationException("Chi co the khoi phuc du lieu bien ban khi cuoc hop da ket thuc");
        }

        minutesPreconditionService.requireReadyForGeneration(meeting);

        Optional<MeetingMinutesJobEntity> latestJob =
            minutesJobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId);
        if (latestJob.map(MeetingMinutesJobEntity::getStatus).filter(this::isActiveJobStatus).isPresent()) {
            throw new BusinessValidationException("Khong the khoi phuc khi bien ban dang duoc xu ly");
        }

        int deletedFailures = deleteByMeetingId("""
            DELETE FROM meeting_transcript_failures
            WHERE minutes_job_id IN (
                SELECT id
                FROM meeting_minutes_jobs
                WHERE meeting_id = :meetingId
            )
            """, meetingId);
        int deletedChunks = deleteByMeetingId("""
            DELETE FROM meeting_transcript_chunks
            WHERE minutes_job_id IN (
                SELECT id
                FROM meeting_minutes_jobs
                WHERE meeting_id = :meetingId
            )
            """, meetingId);
        int deletedMinutes = deleteByMeetingId("""
            DELETE FROM meeting_minutes
            WHERE meeting_id = :meetingId
            """, meetingId);
        int deletedJobs = deleteByMeetingId("""
            DELETE FROM meeting_minutes_jobs
            WHERE meeting_id = :meetingId
            """, meetingId);

        log.info(
            "ADMIN_RESET_MINUTES actorPublicId={} meetingId={} deletedFailures={} deletedChunks={} deletedMinutes={} deletedJobs={}",
            currentUserService.getCurrentPrincipal().map(principal -> principal.publicId()).orElse(null),
            meetingId,
            deletedFailures,
            deletedChunks,
            deletedMinutes,
            deletedJobs
        );
    }

    private int deleteByMeetingId(String sql, Long meetingId) {
        return entityManager.createNativeQuery(sql)
            .setParameter("meetingId", meetingId)
            .executeUpdate();
    }

    private boolean isNoOp(
        LocalDateTime oldStartTime,
        LocalDateTime newStartTime,
        LocalDateTime oldEndTime,
        LocalDateTime newEndTime,
        MeetingStatus oldStatus,
        MeetingStatus newStatus
    ) {
        return java.util.Objects.equals(oldStartTime, newStartTime)
            && java.util.Objects.equals(oldEndTime, newEndTime)
            && oldStatus == newStatus;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isActiveJobStatus(MinutesJobStatus status) {
        return status == MinutesJobStatus.QUEUED || status == MinutesJobStatus.PROCESSING;
    }
}
