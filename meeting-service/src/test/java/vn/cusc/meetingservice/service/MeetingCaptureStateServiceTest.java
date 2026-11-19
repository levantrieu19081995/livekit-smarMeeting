package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@ExtendWith(MockitoExtension.class)
class MeetingCaptureStateServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingCaptureSessionRepository captureRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private CurrentUserService currentUserService;

    private MeetingCaptureStateService stateService;
    private MeetingEntity meeting;

    @BeforeEach
    void setUp() {
        stateService = new MeetingCaptureStateService(
            meetingRepository,
            captureRepository,
            participantRepository,
            currentUserService,
            new MeetingStatusResolver()
        );
        meeting = meeting(MeetingStatus.IN_PROGRESS);
        org.mockito.Mockito.lenient().when(captureRepository.findMeetingIdById(10L)).thenReturn(Optional.of(7L));
        org.mockito.Mockito.lenient().when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
    }

    @Test
    void claimStartLocksMeetingBeforeParticipantPermissionLookup() {
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
        when(captureRepository.saveAndFlush(any(MeetingCaptureSessionEntity.class)))
            .thenAnswer(invocation -> {
                MeetingCaptureSessionEntity session = invocation.getArgument(0);
                session.setId(10L);
                return session;
            });

        var claim = stateService.claimStart(7L);

        InOrder inOrder = inOrder(meetingRepository, participantRepository);
        inOrder.verify(meetingRepository).findByIdForUpdate(7L);
        inOrder.verify(participantRepository).findByMeetingIdAndUserAccountId(7L, 99L);
        assertTrue(claim.remoteStartRequired());
        assertEquals(CaptureStatus.STARTING, claim.session().getStatus());
    }

    @Test
    void claimStartRejectsPersistedCompletedBeforeCaptureSessionSave() {
        meeting.setStatus(MeetingStatus.COMPLETED);
        authorizeLockedMeeting();

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> stateService.claimStart(7L));

        assertEquals("Cuộc họp đã kết thúc hoặc đã bị hủy", exception.getMessage());
        verify(captureRepository, never()).saveAndFlush(any());
    }

    @Test
    void claimStartRejectsCancelled() {
        meeting.setStatus(MeetingStatus.CANCELLED);
        authorizeLockedMeeting();

        assertThrows(BusinessValidationException.class, () -> stateService.claimStart(7L));
        verify(captureRepository, never()).saveAndFlush(any());
    }

    @Test
    void claimStartRejectsTimeDerivedCompleted() {
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusMinutes(1));
        authorizeLockedMeeting();

        assertThrows(BusinessValidationException.class, () -> stateService.claimStart(7L));
        verify(captureRepository, never()).saveAndFlush(any());
    }

    @Test
    void runningRepeatedStartReturnsWithoutRemoteStart() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "session-7", "manifest.json");
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));

        var claim = stateService.claimStart(7L);

        assertFalse(claim.remoteStartRequired());
        assertEquals(session, claim.session());
    }

    @Test
    void stoppingStartIsRejected() {
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L))
            .thenReturn(Optional.of(capture(CaptureStatus.STOPPING, "session-7", null)));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> stateService.claimStart(7L));

        assertEquals("Hệ thống đang hoàn tất bản ghi", exception.getMessage());
    }

    @Test
    void failedWithExternalSessionStartIsRejected() {
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L))
            .thenReturn(Optional.of(capture(CaptureStatus.FAILED, "session-7", null)));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> stateService.claimStart(7L));

        assertEquals(" Cần hoàn tất capture session trước khi bất đầu phiên mới", exception.getMessage());
    }

    @Test
    void claimStopRunningMovesToStopping() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "session-7", null);
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));
        when(captureRepository.saveAndFlush(session)).thenReturn(session);

        var claim = stateService.claimStop(7L);

        assertTrue(claim.remoteStopRequired());
        assertEquals(CaptureStatus.STOPPING, session.getStatus());
        assertNotNull(session.getStoppingAt());
    }

    @Test
    void claimStopStoppingReturnsWithoutDuplicateRemoteClaim() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.STOPPING, "session-7", null);
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));

        var claim = stateService.claimStop(7L);

        assertFalse(claim.remoteStopRequired());
        verify(captureRepository, never()).saveAndFlush(any());
    }

    @Test
    void claimStopFailedWithExternalSessionMovesToStoppingForRetry() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.FAILED, "session-7", null);
        authorizeLockedMeeting();
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));
        when(captureRepository.saveAndFlush(session)).thenReturn(session);

        var claim = stateService.claimStop(7L);

        assertTrue(claim.remoteStopRequired());
        assertEquals(CaptureStatus.STOPPING, session.getStatus());
    }

    @Test
    void claimStopForTerminalTransitionRunningMovesToStopping() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "session-7", null);
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var claim = stateService.claimStopForTerminalTransition(7L);

        assertTrue(claim.remoteStopRequired());
        assertEquals(CaptureStatus.STOPPING, session.getStatus());
    }

    @Test
    void claimStopForTerminalTransitionCompletedWithManifestDoesNotRequireRemoteStop() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.COMPLETED, "session-7", "manifest.json");
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(session));

        var claim = stateService.claimStopForTerminalTransition(7L);

        assertFalse(claim.remoteStopRequired());
    }

    @Test
    void claimStopForTerminalTransitionStartingIsRejected() {
        assertTerminalCaptureNotReady(CaptureStatus.STARTING, "session-7", null);
    }

    @Test
    void claimStopForTerminalTransitionStoppingIsRejected() {
        assertTerminalCaptureNotReady(CaptureStatus.STOPPING, "session-7", null);
    }

    @Test
    void claimStopForTerminalTransitionFailedWithoutExternalSessionIsRejected() {
        assertTerminalCaptureNotReady(CaptureStatus.FAILED, null, null);
    }

    @Test
    void claimStopForTerminalTransitionCompletedWithoutManifestIsRejected() {
        assertTerminalCaptureNotReady(CaptureStatus.COMPLETED, "session-7", null);
    }

    @Test
    void markStartSucceededRequiresRunningResponseAndPersistsRuntimeIdentity() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.STARTING, null, null);
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var saved = stateService.markStartSucceeded(
            10L,
            new CaptureServiceSessionResponse("session-7", "RUNNING", "started", null, null)
        );

        assertEquals(CaptureStatus.RUNNING, saved.getStatus());
        assertEquals("session-7", saved.getExternalSessionId());
        assertNotNull(saved.getStartedAt());
    }

    @Test
    void markStopSucceededRequiresCompletedMatchingSessionAndManifest() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.STOPPING, "session-7", null);
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var saved = stateService.markStopSucceeded(
            10L,
            new CaptureServiceSessionResponse("session-7", "COMPLETED", "stopped", "manifest.json", null)
        );

        assertEquals(CaptureStatus.COMPLETED, saved.getStatus());
        assertEquals("manifest.json", saved.getManifestPath());
        assertNotNull(saved.getCompletedAt());
    }

    @Test
    void claimRecoveryLocksMeetingBeforeCaptureSessionRead() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "session-7", null);
        session.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var claim = stateService.claimRecovery(7L, 10L, LocalDateTime.now().minusSeconds(30));

        InOrder inOrder = inOrder(meetingRepository, captureRepository);
        inOrder.verify(meetingRepository).findByIdForUpdate(7L);
        inOrder.verify(captureRepository).findById(10L);
        assertTrue(claim.isPresent());
    }

    @Test
    void claimRecoveryRejectsCandidateThatIsNoLongerStale() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "session-7", null);
        session.setUpdatedAt(LocalDateTime.now());
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));

        var claim = stateService.claimRecovery(7L, 10L, LocalDateTime.now().minusSeconds(30));

        assertTrue(claim.isEmpty());
        verify(captureRepository, never()).save(any());
    }

    @Test
    void recoveryStartingAdoptsRunningRuntime() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.STARTING, null, null);
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var saved = stateService.markRecoveryRunning(
            new MeetingCaptureStateService.RecoveryClaim(10L, 7L, CaptureStatus.STARTING, null),
            new CaptureServiceStatusResponse(10L, true, "abc", "RUNNING", "running", null, null)
        );

        assertEquals(CaptureStatus.RUNNING, saved.getStatus());
        assertEquals("abc", saved.getExternalSessionId());
        assertNotNull(saved.getStartedAt());
    }

    @Test
    void recoveryCompletedPersistsManifestAndExternalSession() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "abc", null);
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var saved = stateService.markRecoveryCompleted(
            new MeetingCaptureStateService.RecoveryClaim(10L, 7L, CaptureStatus.RUNNING, "abc"),
            new CaptureServiceStatusResponse(10L, false, "abc", "COMPLETED", "done", "manifest.json", "2026-08-21T00:00:00Z")
        );

        assertEquals(CaptureStatus.COMPLETED, saved.getStatus());
        assertEquals("abc", saved.getExternalSessionId());
        assertEquals("manifest.json", saved.getManifestPath());
        assertNotNull(saved.getCompletedAt());
    }

    @Test
    void recoveryDefinitiveFailureClearsExternalSessionAndPreservesManifest() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "abc", "old.json");
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(5);
        session.setStartedAt(startedAt);
        session.setStoppingAt(LocalDateTime.now().minusMinutes(1));
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);

        var saved = stateService.markRecoveryFailed(
            new MeetingCaptureStateService.RecoveryClaim(10L, 7L, CaptureStatus.RUNNING, "abc"),
            "manifest.json",
            "runtime gone"
        );

        assertEquals(CaptureStatus.FAILED, saved.getStatus());
        assertEquals(null, saved.getExternalSessionId());
        assertEquals("manifest.json", saved.getManifestPath());
        assertEquals(startedAt, saved.getStartedAt());
        assertNotNull(saved.getStoppingAt());
    }

    @Test
    void claimRecoveryStopLocksMeetingFirstAndMovesRunningToStopping() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.RUNNING, "abc", null);
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.saveAndFlush(session)).thenReturn(session);

        var claim = stateService.claimRecoveryStop(
            new MeetingCaptureStateService.RecoveryClaim(10L, 7L, CaptureStatus.RUNNING, "abc")
        );

        InOrder inOrder = inOrder(meetingRepository, captureRepository);
        inOrder.verify(meetingRepository).findByIdForUpdate(7L);
        inOrder.verify(captureRepository).findById(10L);
        assertTrue(claim.remoteStopRequired());
        assertEquals(CaptureStatus.STOPPING, session.getStatus());
        assertEquals("abc", session.getExternalSessionId());
        assertNotNull(session.getStoppingAt());
    }

    @Test
    void claimRecoveryStopAllowsLegacyFailedWithExternalSession() {
        MeetingCaptureSessionEntity session = capture(CaptureStatus.FAILED, "abc", null);
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.saveAndFlush(session)).thenReturn(session);

        var claim = stateService.claimRecoveryStop(
            new MeetingCaptureStateService.RecoveryClaim(10L, 7L, CaptureStatus.FAILED, "abc")
        );

        assertTrue(claim.remoteStopRequired());
        assertEquals(CaptureStatus.STOPPING, session.getStatus());
    }

    @Test
    void failedRecorderStopRemainsFailedWithPartialManifestAndDoesNotBlockMeetingEnd() {
        var session = capture(CaptureStatus.STOPPING, "session-7", null);
        when(captureRepository.findById(10L)).thenReturn(Optional.of(session));
        when(captureRepository.save(session)).thenReturn(session);
        var saved = stateService.markStopSucceeded(10L,
            new CaptureServiceSessionResponse("session-7", "FAILED", "audio interrupted", "partial-manifest.json", null));
        assertEquals(CaptureStatus.FAILED, saved.getStatus()); assertNotNull(saved.getCompletedAt());
        assertEquals("partial-manifest.json", saved.getManifestPath()); assertEquals(null, saved.getExternalSessionId());
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L)).thenReturn(Optional.of(saved));
        assertFalse(stateService.claimStopForTerminalTransition(7L).remoteStopRequired());
    }

    private void assertTerminalCaptureNotReady(CaptureStatus status, String externalSessionId, String manifestPath) {
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(7L))
            .thenReturn(Optional.of(capture(status, externalSessionId, manifestPath)));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> stateService.claimStopForTerminalTransition(7L));
 
        assertEquals("Bản ghi âm chưa được hoàn tất", exception.getMessage());
    }

    private void authorizeLockedMeeting() {
        when(meetingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(meeting));
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal()));
        when(participantRepository.findByMeetingIdAndUserAccountId(7L, 99L))
            .thenReturn(Optional.of(participant()));
    }

    private MeetingEntity meeting(MeetingStatus status) {
        MeetingEntity entity = new MeetingEntity();
        entity.setId(7L);
        entity.setRoomName("meeting-7");
        entity.setStartTime(LocalDateTime.now().minusMinutes(30));
        entity.setEndTime(LocalDateTime.now().plusMinutes(30));
        entity.setStatus(status);
        entity.setAccessType(MeetingAccessType.PUBLIC);
        return entity;
    }

    private MeetingCaptureSessionEntity capture(CaptureStatus status, String externalSessionId, String manifestPath) {
        MeetingCaptureSessionEntity session = new MeetingCaptureSessionEntity();
        session.setId(10L);
        session.setMeeting(meeting);
        session.setStatus(status);
        session.setExternalSessionId(externalSessionId);
        session.setManifestPath(manifestPath);
        return session;
    }

    private MeetingParticipantEntity participant() {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setRole(MeetingRole.CHAIRMAN);
        var account = new vn.cusc.meetingservice.entity.UserAccountEntity();
        account.setId(99L);
        meeting.setCreatedByAccount(account);
        return participant;
    }

    private MeetingPrincipal principal() {
        return new MeetingPrincipal(
            99L,
            "public-99",
            "user-99",
            "user99@cusc.vn",
            "User 99",
            null,
            AccountStatus.ACTIVE,
            SystemRole.USER,
            true,
            AuthProvider.LOCAL,
            null
        );
    }
}
