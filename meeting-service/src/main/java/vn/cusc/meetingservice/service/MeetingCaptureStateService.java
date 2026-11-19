package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingStatusResolver;
import vn.cusc.meetingservice.util.MeetingVisibility;
import vn.cusc.meetingservice.util.MeetingPermissions;

@Service
public class MeetingCaptureStateService {

    public record StartClaim(
        MeetingCaptureSessionEntity session,
        String roomName,
        boolean remoteStartRequired
    ) {}

    public record StopClaim(
        MeetingCaptureSessionEntity session,
        boolean remoteStopRequired
    ) {}

    public record RecoveryClaim(
        Long sessionId,
        Long meetingId,
        CaptureStatus status,
        String externalSessionId
    ) {}
 
    private static final String CAPTURE_NOT_READY = "Bản ghi âm chưa được hoàn tất";

    private final MeetingRepository meetingRepository;
    private final MeetingCaptureSessionRepository captureRepository;
    private final MeetingParticipantRepository participantRepository;
    private final CurrentUserService currentUserService;
    private final MeetingStatusResolver statusResolver;

    public MeetingCaptureStateService(
        MeetingRepository meetingRepository,
        MeetingCaptureSessionRepository captureRepository,
        MeetingParticipantRepository participantRepository,
        CurrentUserService currentUserService,
        MeetingStatusResolver statusResolver
    ) {
        this.meetingRepository = meetingRepository;
        this.captureRepository = captureRepository;
        this.participantRepository = participantRepository;
        this.currentUserService = currentUserService;
        this.statusResolver = statusResolver;
    }

    @Transactional(readOnly = true)
    public void requireControlPermissionRead(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meetingId, principal.accountId()).orElse(null);
        if (!MeetingVisibility.canView(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("FORBIDDEN");
        }
    }

    @Transactional
    public StartClaim claimStart(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        requireControlPermission(meeting);

        MeetingStatus effectiveStatus = statusResolver.resolve(meeting);
        if (effectiveStatus == MeetingStatus.COMPLETED || effectiveStatus == MeetingStatus.CANCELLED) {
            throw new BusinessValidationException("Cuộc họp đã kết thúc hoặc đã bị hủy");
        }

        MeetingCaptureSessionEntity latest = captureRepository
            .findFirstByMeetingIdOrderByIdDesc(meetingId)
            .orElse(null);

        if (latest != null && (latest.getStatus() == CaptureStatus.RUNNING
            || latest.getStatus() == CaptureStatus.STARTING)) {
            return new StartClaim(latest, meeting.getRoomName(), false);
        }
        if (latest != null && latest.getStatus() == CaptureStatus.STOPPING) {
            throw new BusinessValidationException("Hệ thống đang hoàn tất bản ghi");
        }
        if (latest != null && latest.getStatus() == CaptureStatus.FAILED
            && !isBlank(latest.getExternalSessionId())) {
            throw new BusinessValidationException(" Cần hoàn tất capture session trước khi bất đầu phiên mới");
        }

        MeetingCaptureSessionEntity session;
        if (latest != null && latest.getStatus() == CaptureStatus.NOT_STARTED) {
            session = latest;
        } else {
            session = new MeetingCaptureSessionEntity();
            session.setMeeting(meeting);
        }

        session.setStatus(CaptureStatus.STARTING);
        session.setErrorMessage(null);
        return new StartClaim(captureRepository.saveAndFlush(session), meeting.getRoomName(), true);
    }

    @Transactional
    public MeetingCaptureSessionEntity markStartSucceeded(Long sessionId, CaptureServiceSessionResponse response) {
        MeetingCaptureSessionEntity session = findSession(sessionId);
        requireStatus(session, CaptureStatus.STARTING);
        if (response == null || isBlank(response.sessionId()) || !"RUNNING".equalsIgnoreCase(response.status())) {
            throw new BusinessValidationException("Capture service không trả về session đang chạy");
        }
        session.setExternalSessionId(response.sessionId());
        session.setStatus(CaptureStatus.RUNNING);
        if (session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        session.setStoppingAt(null);
        session.setCompletedAt(null);
        session.setManifestPath(null);
        session.setErrorMessage(null);
        return captureRepository.save(session);
    }

    @Transactional
    public MeetingCaptureSessionEntity markStartFailed(Long sessionId, String errorMessage) {
        MeetingCaptureSessionEntity session = findSession(sessionId);
        if (session.getStatus() == CaptureStatus.STARTING) {
            session.setStatus(CaptureStatus.FAILED);
            session.setErrorMessage(safeMessage(errorMessage));
            return captureRepository.save(session);
        }
        return session;
    }

    @Transactional
    public StopClaim claimStop(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        requireControlPermission(meeting);
        MeetingCaptureSessionEntity session = captureRepository.findFirstByMeetingIdOrderByIdDesc(meetingId)
            .orElseThrow(() -> new BusinessValidationException("Chưa bất đầu ghi âm"));

        if (session.getStatus() == CaptureStatus.COMPLETED || session.getStatus() == CaptureStatus.STOPPING) {
            return new StopClaim(session, false);
        }
        if (session.getStatus() == CaptureStatus.STARTING) {
            throw new BusinessValidationException("Hệ thống đang bất đầu bản ghi");
        }
        if (session.getStatus() == CaptureStatus.NOT_STARTED
            || (session.getStatus() == CaptureStatus.FAILED && isBlank(session.getExternalSessionId()))) { 
            throw new BusinessValidationException("Chưa có capture session để kết thúc");
        }

        session.setStatus(CaptureStatus.STOPPING);
        session.setStoppingAt(LocalDateTime.now());
        session.setErrorMessage(null);
        return new StopClaim(captureRepository.saveAndFlush(session), true);
    }

    @Transactional
    public MeetingCaptureSessionEntity markStopSucceeded(Long sessionId, CaptureServiceSessionResponse response) {
        MeetingCaptureSessionEntity session = findSession(sessionId);
        requireStatus(session, CaptureStatus.STOPPING);
        if (response == null
            || !("COMPLETED".equalsIgnoreCase(response.status()) || "FAILED".equalsIgnoreCase(response.status()))
            || isBlank(response.sessionId())
            || !response.sessionId().equals(session.getExternalSessionId())
            || isBlank(response.manifestPath())) { 
            throw new BusinessValidationException("Capture service chưa hoàn tất bản ghi");
        }
        boolean failed = "FAILED".equalsIgnoreCase(response.status());
        session.setStatus(failed ? CaptureStatus.FAILED : CaptureStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        session.setManifestPath(response.manifestPath());
        session.setErrorMessage(failed ? safeMessage(response.message()) : null);
        if (failed) session.setExternalSessionId(null);
        return captureRepository.save(session);
    }

    @Transactional
    public MeetingCaptureSessionEntity markStopFailed(Long sessionId, String errorMessage) {
        MeetingCaptureSessionEntity session = findSession(sessionId);
        if (session.getStatus() == CaptureStatus.STOPPING) {
            session.setStatus(CaptureStatus.FAILED);
            session.setErrorMessage(safeMessage(errorMessage));
            return captureRepository.save(session);
        }
        return session;
    }

    @Transactional
    public Optional<RecoveryClaim> claimRecovery(
        Long meetingId,
        Long sessionId,
        LocalDateTime staleBefore
    ) {
        meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        MeetingCaptureSessionEntity session = captureRepository.findById(sessionId).orElse(null);
        if (session == null
            || session.getMeeting() == null
            || !Objects.equals(session.getMeeting().getId(), meetingId)
            || !isRecoveryEligible(session)
            || session.getUpdatedAt() == null
            || !session.getUpdatedAt().isBefore(staleBefore)) {
            return Optional.empty();
        }
        session.setUpdatedAt(LocalDateTime.now());
        MeetingCaptureSessionEntity saved = captureRepository.save(session);
        return Optional.of(new RecoveryClaim(
            saved.getId(),
            meetingId,
            saved.getStatus(),
            saved.getExternalSessionId()
        ));
    }

    @Transactional
    public MeetingCaptureSessionEntity markRecoveryRunning(
        RecoveryClaim claim,
        CaptureServiceStatusResponse response
    ) {
        MeetingCaptureSessionEntity session = findStillMatchingRecoverySession(claim);
        requireStatus(session, CaptureStatus.STARTING);
        if (response == null || isBlank(response.sessionId()) || !"RUNNING".equalsIgnoreCase(response.status())) {
            throw new BusinessValidationException("Capture recovery did not observe running session");
        }
        session.setExternalSessionId(response.sessionId());
        session.setStatus(CaptureStatus.RUNNING);
        if (session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        session.setErrorMessage(null);
        return captureRepository.save(session);
    }

    @Transactional
    public MeetingCaptureSessionEntity markRecoveryCompleted(
        RecoveryClaim claim,
        CaptureServiceStatusResponse response
    ) {
        MeetingCaptureSessionEntity session = findStillMatchingRecoverySession(claim);
        if (!isRecoveryEligible(session)) {
            throw new BusinessValidationException("Capture session Không ở trạng thái phù họp");
        }
        if (response == null
            || isBlank(response.sessionId())
            || !"COMPLETED".equalsIgnoreCase(response.status())
            || isBlank(response.manifestPath())) {
            throw new BusinessValidationException("Capture recovery did not observe completed manifest");
        }
        session.setExternalSessionId(response.sessionId());
        session.setStatus(CaptureStatus.COMPLETED);
        if (session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        session.setCompletedAt(LocalDateTime.now());
        session.setManifestPath(response.manifestPath());
        session.setErrorMessage(null);
        return captureRepository.save(session);
    }

    @Transactional
    public MeetingCaptureSessionEntity markRecoveryFailed(
        RecoveryClaim claim,
        String manifestPath,
        String errorMessage
    ) {
        MeetingCaptureSessionEntity session = findStillMatchingRecoverySession(claim);
        if (!isRecoveryEligible(session)) {
            throw new BusinessValidationException("Capture session Không ở trạng thái phù họp");
        }
        session.setStatus(CaptureStatus.FAILED);
        session.setExternalSessionId(null);
        session.setCompletedAt(LocalDateTime.now());
        if (!isBlank(manifestPath)) {
            session.setManifestPath(manifestPath);
        }
        session.setErrorMessage(safeMessage(errorMessage));
        return captureRepository.save(session);
    }

    @Transactional
    public StopClaim claimRecoveryStop(RecoveryClaim claim) {
        MeetingCaptureSessionEntity session = findStillMatchingRecoverySession(claim);
        if (isBlank(claim.externalSessionId())
            || isBlank(session.getExternalSessionId())
            || !session.getExternalSessionId().equals(claim.externalSessionId())) {
            throw new BusinessValidationException("Capture recovery stop identity mismatch");
        }
        if (session.getStatus() != CaptureStatus.RUNNING
            && session.getStatus() != CaptureStatus.STOPPING
            && !(session.getStatus() == CaptureStatus.FAILED && !isBlank(session.getExternalSessionId()))) {
            throw new BusinessValidationException("Capture session Không ở trạng thái phù họ");
        }
        session.setStatus(CaptureStatus.STOPPING);
        if (session.getStoppingAt() == null) {
            session.setStoppingAt(LocalDateTime.now());
        }
        session.setErrorMessage(null);
        return new StopClaim(captureRepository.saveAndFlush(session), true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StopClaim claimStopForTerminalTransition(Long meetingId) {
        meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        MeetingCaptureSessionEntity session = captureRepository.findFirstByMeetingIdOrderByIdDesc(meetingId)
            .orElse(null);

        if (session == null || session.getStatus() == CaptureStatus.NOT_STARTED) {
            return new StopClaim(session, false);
        }
        if (session.getStatus() == CaptureStatus.COMPLETED) {
            if (isBlank(session.getManifestPath())) {
                throw new BusinessValidationException(CAPTURE_NOT_READY);
            }
            return new StopClaim(session, false);
        }
        if (session.getStatus() == CaptureStatus.FAILED && isBlank(session.getExternalSessionId())
            && session.getCompletedAt() != null) return new StopClaim(session, false);
        if (session.getStatus() == CaptureStatus.STARTING
            || session.getStatus() == CaptureStatus.STOPPING
            || (session.getStatus() == CaptureStatus.FAILED && isBlank(session.getExternalSessionId()))) {
            throw new BusinessValidationException(CAPTURE_NOT_READY);
        }

        session.setStatus(CaptureStatus.STOPPING);
        session.setStoppingAt(LocalDateTime.now());
        session.setErrorMessage(null);
        return new StopClaim(captureRepository.save(session), true);
    }

    private void requireControlPermission(MeetingEntity meeting) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingParticipantEntity participant = participantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId())
            .orElse(null);
        if (!MeetingPermissions.canManage(principal, meeting, participant)) {
            throw new MeetingAccessDeniedException("CAPTURE_CONTROL_FORBIDDEN");
        }
    }

    private MeetingCaptureSessionEntity findSession(Long sessionId) {
        // Scalar lookup avoids caching an old session before waiting for the common meeting lock.
        Long meetingId = captureRepository.findMeetingIdById(sessionId)
            .orElseThrow(() -> new BusinessValidationException("Không tìm thấy capture session"));
        meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        return findSessionAfterMeetingLock(sessionId);
    }

    private MeetingCaptureSessionEntity findSessionAfterMeetingLock(Long sessionId) {
        return captureRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessValidationException("Không tìm thấy capture session"));
    }

    private MeetingCaptureSessionEntity findStillMatchingRecoverySession(RecoveryClaim claim) {
        meetingRepository.findByIdForUpdate(claim.meetingId())
            .orElseThrow(() -> new MeetingNotFoundException(claim.meetingId()));
        MeetingCaptureSessionEntity session = findSessionAfterMeetingLock(claim.sessionId());
        if (session.getMeeting() == null
            || !Objects.equals(session.getMeeting().getId(), claim.meetingId())
            || session.getStatus() != claim.status()
            || !Objects.equals(session.getExternalSessionId(), claim.externalSessionId())) {
            throw new BusinessValidationException("Capture recovery claim no longer matches session");
        }
        return session;
    }

    private boolean isRecoveryEligible(MeetingCaptureSessionEntity session) {
        return session.getStatus() == CaptureStatus.STARTING
            || session.getStatus() == CaptureStatus.RUNNING
            || session.getStatus() == CaptureStatus.STOPPING
            || (session.getStatus() == CaptureStatus.FAILED && !isBlank(session.getExternalSessionId()));
    }

    private void requireStatus(MeetingCaptureSessionEntity session, CaptureStatus status) {
        if (session.getStatus() != status) {
            throw new BusinessValidationException("Capture session Không ở trạng thái phù họp");
        }
    }

    private String safeMessage(String message) {
        return isBlank(message) ? "Capture operation failed" : message;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
