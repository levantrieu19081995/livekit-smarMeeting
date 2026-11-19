package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.client.capture.CaptureClient;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.RecoveryClaim;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StopClaim;

@Service
public class MeetingCaptureRecoveryService {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MeetingCaptureRecoveryService.class);
    private static final List<CaptureStatus> ACTIVE_STATUSES = List.of(
        CaptureStatus.STARTING,
        CaptureStatus.RUNNING,
        CaptureStatus.STOPPING
    );

    private final MeetingCaptureSessionRepository captureRepository;
    private final CaptureClient captureClient;
    private final MeetingCaptureStateService stateService;
    private final long staleAfterSeconds;

    public MeetingCaptureRecoveryService(
        MeetingCaptureSessionRepository captureRepository,
        CaptureClient captureClient,
        MeetingCaptureStateService stateService,
        @Value("${capture.recovery.stale-after-seconds:30}") long staleAfterSeconds
    ) {
        if (staleAfterSeconds < 1) {
            throw new IllegalArgumentException("staleAfterSeconds must be at least 1");
        }
        this.captureRepository = captureRepository;
        this.captureClient = captureClient;
        this.stateService = stateService;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    @Scheduled(
        fixedDelayString =
            "${capture.recovery.interval-ms:30000}"
    )
    public void recoverOneStaleSession() {
        LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(staleAfterSeconds);
        captureRepository.findFirstByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            ACTIVE_STATUSES,
            staleBefore
        ).or(() -> captureRepository
            .findFirstByStatusAndExternalSessionIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
                CaptureStatus.FAILED,
                staleBefore
            ))
            .flatMap(candidate -> stateService.claimRecovery(
                candidate.getMeeting().getId(),
                candidate.getId(),
                staleBefore
            ))
            .ifPresent(this::recover);
    }

    /** Browser observations also reconcile health; the DB claim limits polling to once per session per five seconds. */
    public void refreshObservedSession(Long meetingId) {
        captureRepository.findFirstByMeetingIdOrderByIdDesc(meetingId)
            .flatMap(candidate -> stateService.claimRecovery(meetingId, candidate.getId(), LocalDateTime.now().minusSeconds(5)))
            .ifPresent(this::recover);
    }

    private void recover(RecoveryClaim claim) {
        CaptureServiceStatusResponse remote;
        try {
            remote = captureClient.status(
                claim.meetingId(),
                claim.sessionId(),
                claim.externalSessionId()
            );
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Capture recovery status check failed: meetingId={}, captureSessionId={}, externalSessionId={}, error={}",
                claim.meetingId(),
                claim.sessionId(),
                claim.externalSessionId(),
                exception.getMessage()
            );
            return;
        }

        if (!hasValidIdentity(claim, remote)) {
            LOGGER.warn(
                "Capture recovery ignored identity mismatch: meetingId={}, captureSessionId={}, externalSessionId={}, remoteCaptureSessionId={}, remoteSessionId={}",
                claim.meetingId(),
                claim.sessionId(),
                claim.externalSessionId(),
                remote == null ? null : remote.captureSessionId(),
                remote == null ? null : remote.sessionId()
            );
            return;
        }

        reconcile(claim, remote);
    }

    private void reconcile(RecoveryClaim claim, CaptureServiceStatusResponse remote) {
        String status = normalize(remote.status());
        if ("COMPLETED".equals(status)) {
            if (isBlank(remote.sessionId()) || isBlank(remote.manifestPath())) {
                LOGGER.warn(
                    "Capture recovery ignored incomplete COMPLETED evidence: captureSessionId={}",
                    claim.sessionId()
                );
                return;
            }
            stateService.markRecoveryCompleted(claim, remote);
            return;
        }
        if ("RUNNING".equals(status)) {
            if (Boolean.TRUE.equals(remote.runtimePresent())) {
                handleRuntimeRunning(claim, remote);
            } else {
                markDefinitiveFailed(claim, remote, "Capture runtime missing with unfinalized RUNNING manifest");
            }
            return;
        }
        if ("STARTING".equals(status) && Boolean.TRUE.equals(remote.runtimePresent())) {
            return;
        }
        if ("STOPPING".equals(status) && Boolean.TRUE.equals(remote.runtimePresent())) {
            return;
        }
        if ("FAILED".equals(status)) {
            if (Boolean.TRUE.equals(remote.runtimePresent()) && !isBlank(claim.externalSessionId())) {
                retryStop(claim);
            } else {
                markDefinitiveFailed(claim, remote, remote.message());
            }
            return;
        }
        if ("NOT_FOUND".equals(status)) {
            markDefinitiveFailed(claim, remote, "Exact capture session not found during recovery");
            return;
        }
        LOGGER.warn(
            "Capture recovery ignored unexpected status: captureSessionId={}, status={}",
            claim.sessionId(),
            remote.status()
        );
    }

    private void handleRuntimeRunning(RecoveryClaim claim, CaptureServiceStatusResponse remote) {
        if (claim.status() == CaptureStatus.STARTING) {
            stateService.markRecoveryRunning(claim, remote);
            return;
        }
        if (claim.status() == CaptureStatus.STOPPING || claim.status() == CaptureStatus.FAILED) {
            retryStop(claim);
        }
    }

    private void retryStop(RecoveryClaim claim) {
        StopClaim stopClaim = stateService.claimRecoveryStop(claim);
        if (!stopClaim.remoteStopRequired()) {
            return;
        }
        try {
            CaptureServiceSessionResponse response = captureClient.stop(
                claim.meetingId(),
                new StopCaptureRequest(claim.meetingId(), stopClaim.session().getExternalSessionId())
            );
            stateService.markStopSucceeded(stopClaim.session().getId(), response);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Capture recovery stop retry failed: meetingId={}, captureSessionId={}, externalSessionId={}, error={}",
                claim.meetingId(),
                claim.sessionId(),
                claim.externalSessionId(),
                exception.getMessage()
            );
        }
    }

    private void markDefinitiveFailed(
        RecoveryClaim claim,
        CaptureServiceStatusResponse remote,
        String message
    ) {
        stateService.markRecoveryFailed(claim, remote.manifestPath(), message);
    }

    private boolean hasValidIdentity(RecoveryClaim claim, CaptureServiceStatusResponse remote) {
        if (remote == null
            || remote.captureSessionId() == null
            || !remote.captureSessionId().equals(claim.sessionId())) {
            return false;
        }
        return isBlank(claim.externalSessionId())
            || isBlank(remote.sessionId())
            || remote.sessionId().equals(claim.externalSessionId());
    }

    private String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
