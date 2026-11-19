package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.client.capture.CaptureClient;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.StartCaptureRequest;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StartClaim;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StopClaim;
import vn.cusc.meetingservice.service.impl.MeetingCaptureServiceImpl;

@ExtendWith(MockitoExtension.class)
class MeetingCaptureServiceTest {

    @Mock private MeetingCaptureStateService stateService;
    @Mock private MeetingCaptureSessionRepository captureRepository;
    @Mock private CaptureClient captureClient;

    private MeetingCaptureService captureService;
    private MeetingCaptureSessionEntity session;

    @BeforeEach
    void setUp() {
        LiveKitProperties liveKitProperties = new LiveKitProperties();
        liveKitProperties.setUrl("ws://livekit:7880");
        captureService = new MeetingCaptureServiceImpl(
            stateService,
            captureRepository,
            captureClient,
            liveKitProperties
        );

        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(7L);
        meeting.setRoomName("meeting-7");
        session = new MeetingCaptureSessionEntity();
        session.setId(10L);
        session.setMeeting(meeting);
        session.setExternalSessionId("session-7");
        session.setStatus(CaptureStatus.RUNNING);
    }

    @Test
    void startClaimRequiresRemoteCallsCaptureAndMarksSucceeded() {
        MeetingCaptureSessionEntity starting = capture(CaptureStatus.STARTING, null, null);
        MeetingCaptureSessionEntity running = capture(CaptureStatus.RUNNING, "session-7", null);
        CaptureServiceSessionResponse remoteResponse =
            new CaptureServiceSessionResponse("session-7", "RUNNING", "started", null, null);
        when(stateService.claimStart(7L)).thenReturn(new StartClaim(starting, "meeting-7", true));
        when(captureClient.start(any(StartCaptureRequest.class))).thenReturn(remoteResponse);
        when(stateService.markStartSucceeded(10L, remoteResponse)).thenReturn(running);

        var response = captureService.start(7L);

        assertEquals(CaptureStatus.RUNNING, response.status());
        ArgumentCaptor<StartCaptureRequest> requestCaptor = ArgumentCaptor.forClass(StartCaptureRequest.class);
        verify(captureClient).start(requestCaptor.capture());
        assertEquals(10L, requestCaptor.getValue().captureSessionId());
        verify(stateService).markStartSucceeded(10L, remoteResponse);
    }

    @Test
    void startClaimAlreadyRunningDoesNotCallCaptureClient() {
        when(stateService.claimStart(7L)).thenReturn(new StartClaim(session, "meeting-7", false));

        var response = captureService.start(7L);

        assertEquals(CaptureStatus.RUNNING, response.status());
        verify(captureClient, never()).start(any());
    }

    @Test
    void remoteStartFailureLeavesStartingForRecoveryAndPropagates() {
        MeetingCaptureSessionEntity starting = capture(CaptureStatus.STARTING, null, null);
        RuntimeException failure = new RuntimeException("capture temporarily unavailable");
        when(stateService.claimStart(7L)).thenReturn(new StartClaim(starting, "meeting-7", true));
        when(captureClient.start(any(StartCaptureRequest.class))).thenThrow(failure);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> captureService.start(7L));

        assertEquals(failure, exception);
        verify(stateService, never()).markStartFailed(any(), any());
    }

    @Test
    void stopClaimRequiresRemoteCallsCaptureAndMarksSucceeded() {
        MeetingCaptureSessionEntity completed = capture(CaptureStatus.COMPLETED, "session-7", "manifest.json");
        CaptureServiceSessionResponse remoteResponse =
            new CaptureServiceSessionResponse("session-7", "COMPLETED", "stopped", "manifest.json", null);
        when(stateService.claimStop(7L)).thenReturn(new StopClaim(session, true));
        when(captureClient.stop(any(), any(StopCaptureRequest.class))).thenReturn(remoteResponse);
        when(stateService.markStopSucceeded(10L, remoteResponse)).thenReturn(completed);

        var response = captureService.stop(7L);

        assertEquals(CaptureStatus.COMPLETED, response.status());
        verify(captureClient).stop(any(), any(StopCaptureRequest.class));
        verify(stateService).markStopSucceeded(10L, remoteResponse);
    }

    @Test
    void noRemoteStopClaimDoesNotCallCaptureClient() {
        MeetingCaptureSessionEntity stopping = capture(CaptureStatus.STOPPING, "session-7", null);
        when(stateService.claimStop(7L)).thenReturn(new StopClaim(stopping, false));

        var response = captureService.stop(7L);

        assertEquals(CaptureStatus.STOPPING, response.status());
        verify(captureClient, never()).stop(any(), any());
    }

    @Test
    void manualRemoteStopFailureLeavesStoppingForRecoveryAndPropagates() {
        RuntimeException failure = new RuntimeException("capture temporarily unavailable");
        when(stateService.claimStop(7L)).thenReturn(new StopClaim(session, true));
        when(captureClient.stop(any(), any(StopCaptureRequest.class))).thenThrow(failure);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> captureService.stop(7L));

        assertEquals(failure, exception);
        verify(stateService, never()).markStopFailed(any(), any());
    }

    @Test
    void finalizeForTerminalTransitionRunningStopsRemoteAndMarksSucceeded() {
        MeetingCaptureSessionEntity completed = capture(CaptureStatus.COMPLETED, "session-7", "manifest.json");
        CaptureServiceSessionResponse remoteResponse =
            new CaptureServiceSessionResponse("session-7", "COMPLETED", "stopped", "manifest.json", null);
        when(stateService.claimStopForTerminalTransition(7L)).thenReturn(new StopClaim(session, true));
        when(captureClient.stop(any(), any(StopCaptureRequest.class))).thenReturn(remoteResponse);
        when(stateService.markStopSucceeded(10L, remoteResponse)).thenReturn(completed);

        captureService.finalizeForTerminalTransition(7L);

        verify(captureClient).stop(any(), any(StopCaptureRequest.class));
        verify(stateService).markStopSucceeded(10L, remoteResponse);
    }

    @Test
    void finalizeForTerminalTransitionRemoteFailurePropagatesWithoutMarkingStopFailed() {
        RuntimeException failure = new RuntimeException("capture temporarily unavailable");
        when(stateService.claimStopForTerminalTransition(7L)).thenReturn(new StopClaim(session, true));
        when(captureClient.stop(any(), any(StopCaptureRequest.class))).thenThrow(failure);

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> captureService.finalizeForTerminalTransition(7L));

        assertEquals(failure, exception);
        verify(stateService, never()).markStopFailed(any(), any());
    }

    @Test
    void getStatusChecksReadPermissionBeforeRepositoryRead() {
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));

        var response = captureService.getStatus(7L);

        assertEquals(CaptureStatus.RUNNING, response.status());
        verify(stateService).requireControlPermissionRead(7L);
    }

    private MeetingCaptureSessionEntity capture(CaptureStatus status, String externalSessionId, String manifestPath) {
        MeetingCaptureSessionEntity capture = new MeetingCaptureSessionEntity();
        capture.setId(10L);
        capture.setMeeting(session.getMeeting());
        capture.setExternalSessionId(externalSessionId);
        capture.setStatus(status);
        capture.setManifestPath(manifestPath);
        return capture;
    }
}
