package vn.cusc.meetingservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.client.capture.CaptureClient;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.dto.capture.StartCaptureRequest;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.RecoveryClaim;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StopClaim;

@ExtendWith(MockitoExtension.class)
class MeetingCaptureRecoveryServiceTest {

    @Mock private MeetingCaptureSessionRepository captureRepository;
    @Mock private CaptureClient captureClient;
    @Mock private MeetingCaptureStateService stateService;

    private MeetingCaptureRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new MeetingCaptureRecoveryService(
            captureRepository,
            captureClient,
            stateService,
            30
        );
    }

    @Test
    void startingRuntimeRunningAdoptsRuntimeWithoutStart() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        CaptureServiceStatusResponse remote = remote(true, "abc", "RUNNING", null);
        when(captureClient.status(7L, 10L, null)).thenReturn(remote);

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryRunning(claim, remote);
        verify(captureClient, never()).start(any(StartCaptureRequest.class));
    }

    @Test
    void startingWithLiveRuntimeStartingRemainsUnresolved() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        when(captureClient.status(7L, 10L, null))
            .thenReturn(remote(true, null, "STARTING", null));

        recoveryService.recoverOneStaleSession();

        verify(stateService, never()).markRecoveryFailed(eq(claim), any(), any());
        verify(stateService, never()).claimRecoveryStop(any());
        verify(captureClient, never()).stop(any(), any(StopCaptureRequest.class));
        verify(captureClient, never()).start(any(StartCaptureRequest.class));
        verify(stateService, never()).markRecoveryRunning(any(), any());
    }

    @Test
    void startingDiskCompletedAppliesCompleted() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        CaptureServiceStatusResponse remote = remote(false, "abc", "COMPLETED", "manifest.json");
        when(captureClient.status(7L, 10L, null)).thenReturn(remote);

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryCompleted(claim, remote);
    }

    @Test
    void startingExactNotFoundMarksDefinitiveFailed() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        when(captureClient.status(7L, 10L, null))
            .thenReturn(remote(false, null, "NOT_FOUND", null));

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryFailed(eq(claim), eq(null), any());
    }

    @Test
    void statusExceptionDoesNotFailCapture() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        when(captureClient.status(7L, 10L, null)).thenThrow(new RuntimeException("timeout"));

        recoveryService.recoverOneStaleSession();

        verify(stateService, never()).markRecoveryFailed(eq(claim), any(), any());
    }

    @Test
    void runningRuntimeRunningRemainsRunningWithoutStop() {
        arrangeClaim(CaptureStatus.RUNNING, "abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "RUNNING", null));

        recoveryService.recoverOneStaleSession();

        verify(captureClient, never()).stop(any(), any(StopCaptureRequest.class));
    }

    @Test
    void runningDiskCompletedAppliesCompleted() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.RUNNING, "abc");
        CaptureServiceStatusResponse remote = remote(false, "abc", "COMPLETED", "manifest.json");
        when(captureClient.status(7L, 10L, "abc")).thenReturn(remote);

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryCompleted(claim, remote);
    }

    @Test
    void runningAbsentRuntimeWithRunningManifestFails() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.RUNNING, "abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(false, "abc", "RUNNING", "manifest.json"));

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryFailed(eq(claim), eq("manifest.json"), any());
    }

    @Test
    void runningRuntimeFailedRetriesStop() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.RUNNING, "abc");
        MeetingCaptureSessionEntity stopping = capture(CaptureStatus.STOPPING);
        stopping.setExternalSessionId("abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "FAILED", null));
        when(stateService.claimRecoveryStop(claim)).thenReturn(new StopClaim(stopping, true));
        when(captureClient.stop(eq(7L), any(StopCaptureRequest.class)))
            .thenReturn(new CaptureServiceSessionResponse("abc", "COMPLETED", "done", "manifest.json", null));

        recoveryService.recoverOneStaleSession();

        verify(captureClient).stop(eq(7L), any(StopCaptureRequest.class));
    }

    @Test
    void stoppingRuntimeRunningRetriesStop() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STOPPING, "abc");
        MeetingCaptureSessionEntity stopping = capture(CaptureStatus.STOPPING);
        stopping.setExternalSessionId("abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "RUNNING", null));
        when(stateService.claimRecoveryStop(claim)).thenReturn(new StopClaim(stopping, true));
        when(captureClient.stop(eq(7L), any(StopCaptureRequest.class)))
            .thenReturn(new CaptureServiceSessionResponse("abc", "COMPLETED", "done", "manifest.json", null));

        recoveryService.recoverOneStaleSession();

        verify(captureClient).stop(eq(7L), any(StopCaptureRequest.class));
    }

    @Test
    void stoppingRuntimeStoppingDoesNotDuplicateStop() {
        arrangeClaim(CaptureStatus.STOPPING, "abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "STOPPING", null));

        recoveryService.recoverOneStaleSession();

        verify(captureClient, never()).stop(any(), any(StopCaptureRequest.class));
    }

    @Test
    void stoppingDiskCompletedAppliesCompleted() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STOPPING, "abc");
        CaptureServiceStatusResponse remote = remote(false, "abc", "COMPLETED", "manifest.json");
        when(captureClient.status(7L, 10L, "abc")).thenReturn(remote);

        recoveryService.recoverOneStaleSession();

        verify(stateService).markRecoveryCompleted(claim, remote);
    }

    @Test
    void stoppingStatusExceptionStaysUnresolved() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STOPPING, "abc");
        when(captureClient.status(7L, 10L, "abc")).thenThrow(new RuntimeException("timeout"));

        recoveryService.recoverOneStaleSession();

        verify(stateService, never()).markRecoveryFailed(eq(claim), any(), any());
    }

    @Test
    void legacyFailedWithExternalRuntimeRunningRetriesStop() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.FAILED, "abc");
        MeetingCaptureSessionEntity stopping = capture(CaptureStatus.STOPPING);
        stopping.setExternalSessionId("abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "RUNNING", null));
        when(stateService.claimRecoveryStop(claim)).thenReturn(new StopClaim(stopping, true));
        when(captureClient.stop(eq(7L), any(StopCaptureRequest.class)))
            .thenReturn(new CaptureServiceSessionResponse("abc", "COMPLETED", "done", "manifest.json", null));

        recoveryService.recoverOneStaleSession();

        verify(captureClient).stop(eq(7L), any(StopCaptureRequest.class));
    }

    @Test
    void identityMismatchDoesNotTransitionOrStop() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.RUNNING, "abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(new CaptureServiceStatusResponse(11L, true, "abc", "RUNNING", "wrong", null, null));

        recoveryService.recoverOneStaleSession();

        verify(stateService, never()).markRecoveryRunning(any(), any());
        verify(stateService, never()).markRecoveryCompleted(any(), any());
        verify(stateService, never()).markRecoveryFailed(eq(claim), any(), any());
        verify(captureClient, never()).stop(any(), any(StopCaptureRequest.class));
    }

    @Test
    void stopRetryExceptionLeavesStoppingForFutureRecovery() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STOPPING, "abc");
        MeetingCaptureSessionEntity stopping = capture(CaptureStatus.STOPPING);
        stopping.setExternalSessionId("abc");
        when(captureClient.status(7L, 10L, "abc"))
            .thenReturn(remote(true, "abc", "RUNNING", null));
        when(stateService.claimRecoveryStop(claim)).thenReturn(new StopClaim(stopping, true));
        when(captureClient.stop(eq(7L), any(StopCaptureRequest.class)))
            .thenThrow(new RuntimeException("timeout"));

        recoveryService.recoverOneStaleSession();

        verify(stateService, never()).markStopFailed(any(), any());
    }

    @Test
    void processesOnlyOneSuccessfullyClaimedCandidatePerInvocation() {
        RecoveryClaim claim = arrangeClaim(CaptureStatus.STARTING, null);
        CaptureServiceStatusResponse remote = remote(true, "abc", "RUNNING", null);
        when(captureClient.status(7L, 10L, null)).thenReturn(remote);

        recoveryService.recoverOneStaleSession();

        verify(stateService).claimRecovery(eq(7L), eq(10L), any(LocalDateTime.class));
        verify(captureClient).status(7L, 10L, null);
        verify(stateService).markRecoveryRunning(claim, remote);
    }

    private RecoveryClaim arrangeClaim(CaptureStatus status, String externalSessionId) {
        MeetingCaptureSessionEntity candidate = capture(status);
        candidate.setExternalSessionId(externalSessionId);
        when(captureRepository.findFirstByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(),
            any(LocalDateTime.class)
        )).thenReturn(status == CaptureStatus.FAILED ? Optional.empty() : Optional.of(candidate));
        if (status == CaptureStatus.FAILED) {
            when(captureRepository.findFirstByStatusAndExternalSessionIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
                eq(CaptureStatus.FAILED),
                any(LocalDateTime.class)
            )).thenReturn(Optional.of(candidate));
        }
        RecoveryClaim claim = new RecoveryClaim(10L, 7L, status, externalSessionId);
        when(stateService.claimRecovery(eq(7L), eq(10L), any(LocalDateTime.class)))
            .thenReturn(Optional.of(claim));
        return claim;
    }

    private CaptureServiceStatusResponse remote(
        boolean runtimePresent,
        String sessionId,
        String status,
        String manifestPath
    ) {
        return new CaptureServiceStatusResponse(
            10L,
            runtimePresent,
            sessionId,
            status,
            status,
            manifestPath,
            null
        );
    }

    private MeetingCaptureSessionEntity capture(CaptureStatus status) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(7L);
        MeetingCaptureSessionEntity capture = new MeetingCaptureSessionEntity();
        capture.setId(10L);
        capture.setMeeting(meeting);
        capture.setStatus(status);
        capture.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        return capture;
    }
}
