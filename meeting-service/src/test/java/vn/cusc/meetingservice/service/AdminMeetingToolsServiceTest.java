package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.request.AdminMeetingOverrideRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.util.MeetingStatusResolver;

@ExtendWith(MockitoExtension.class)
class AdminMeetingToolsServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingMinutesJobRepository minutesJobRepository;
    @Mock private MeetingMinutesPreconditionService minutesPreconditionService;
    @Mock private CurrentUserService currentUserService;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;
    @Mock private vn.cusc.meetingservice.repository.MeetingParticipantRepository participants;
    @Mock private MeetingCaptureService capture;
    @Mock private MeetingPollTerminalService polls;
    @Mock private MeetingMediaCleanupService media;
    @Mock private MeetingSpeakingService speaking;

    private AdminMeetingToolsService service;

    @BeforeEach
    void setUp() {
        service = new AdminMeetingToolsService(
            meetingRepository,
            minutesJobRepository,
            minutesPreconditionService,
            new MeetingStatusResolver(),
            currentUserService,
            entityManager,
            new MeetingAccessPolicyService(currentUserService, participants)
        );
        lenient().when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(principal()));
        service.setTerminalServices(capture, polls, media);
        service.setMeetingSpeakingService(speaking);
    }

    @Test
    void cancellationFinalizesAllMeetingFlowsAndQueuesRoomCleanup() {
        var meeting = meeting(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), MeetingStatus.IN_PROGRESS);
        meeting.setRoomName("meeting-test-admin");
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.CANCELLED, "Cancel"));
        var order = inOrder(capture, polls, speaking, meetingRepository, media);
        order.verify(capture).finalizeForTerminalTransition(10L);
        order.verify(polls).closeForTerminalTransition(10L);
        order.verify(speaking).finalizeForTerminalTransition(10L);
        order.verify(meetingRepository).save(meeting);
        order.verify(media).enqueue(10L, "DELETE_ROOM", "meeting-test-admin", null);
        assertEquals(MeetingStatus.CANCELLED, meeting.getStatus());
    }

    @Test
    void datelessInstantAndUnusedLaterMeetingsCanBeCancelledWithoutNullScheduleErrors() {
        for (var mode : new vn.cusc.meetingservice.model.enums.MeetingCreationMode[]{
            vn.cusc.meetingservice.model.enums.MeetingCreationMode.INSTANT,
            vn.cusc.meetingservice.model.enums.MeetingCreationMode.LATER}) {
            var value = meeting(mode == vn.cusc.meetingservice.model.enums.MeetingCreationMode.LATER ? null : LocalDateTime.now(),
                null, mode == vn.cusc.meetingservice.model.enums.MeetingCreationMode.LATER ? MeetingStatus.UPCOMING : MeetingStatus.IN_PROGRESS);
            value.setCreationMode(mode);
            when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(value));
            service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.CANCELLED, "Cancel link"));
            assertEquals(MeetingStatus.CANCELLED, value.getStatus());
        }
        verify(capture, org.mockito.Mockito.times(2)).finalizeForTerminalTransition(10L);
        verify(polls, org.mockito.Mockito.times(2)).closeForTerminalTransition(10L);
    }

    @Test
    void reopeningWaitsForOldRoomCleanupBeforeAcceptingNewConnections() {
        var meeting = meeting(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(1), MeetingStatus.CANCELLED);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        when(media.pending(10L)).thenReturn(true);
        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(10L,
            new AdminMeetingOverrideRequest(null, null, MeetingStatus.UPCOMING, "Reopen")));
        verify(meetingRepository, never()).save(any());
        assertEquals(MeetingStatus.CANCELLED, meeting.getStatus());
    }

    @Test
    void adminOpenMeetingValidationUsesItsCreationModeDuringClockCorrection() {
        var meeting = meeting(LocalDateTime.now().plusHours(1), null, MeetingStatus.CANCELLED);
        meeting.setCreationMode(vn.cusc.meetingservice.model.enums.MeetingCreationMode.LATER);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.IN_PROGRESS, "Reopen"));
        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
    }

    @Test
    void timeOnlyChangePreservesPersistedStatus() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(1),
            LocalDateTime.now().plusHours(10),
            MeetingStatus.IN_PROGRESS
        );
        LocalDateTime newEndTime = LocalDateTime.now().plusMinutes(30);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, newEndTime, null, null));

        assertEquals(newEndTime, meeting.getEndTime());
        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
        verify(meetingRepository).save(meeting);
    }

    @Test
    void validStartAndEndChangeIsSaved() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.UPCOMING
        );
        LocalDateTime newStartTime = LocalDateTime.now().plusDays(2);
        LocalDateTime newEndTime = newStartTime.plusHours(2);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(newStartTime, newEndTime, null, null));

        assertEquals(newStartTime, meeting.getStartTime());
        assertEquals(newEndTime, meeting.getEndTime());
        verify(meetingRepository).save(meeting);
    }

    @Test
    void endTimeMustBeAfterStartTime() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.UPCOMING
        );
        LocalDateTime newStartTime = LocalDateTime.now().plusDays(2);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(newStartTime, newStartTime, null, null)
        ));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void allPatchFieldsNullIsRejected() {
        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(null, null, null, "reason")
        ));

        assertEquals("Khong co du lieu thay doi", exception.getMessage());
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void completedStatusIsRejected() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(1),
            LocalDateTime.now().plusHours(1),
            MeetingStatus.IN_PROGRESS
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(null, null, MeetingStatus.COMPLETED, "done")
        ));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void completedToInProgressWithCurrentScheduleSucceeds() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(1),
            LocalDateTime.now().plusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.IN_PROGRESS, "test lai"));

        assertEquals(MeetingStatus.IN_PROGRESS, meeting.getStatus());
        verify(meetingRepository).save(meeting);
    }

    @Test
    void completedToInProgressWithPastEndIsRejected() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(null, null, MeetingStatus.IN_PROGRESS, "test lai")
        ));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void cancelledToUpcomingWithFutureStartSucceeds() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.CANCELLED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.UPCOMING, "mo lai lich"));

        assertEquals(MeetingStatus.UPCOMING, meeting.getStatus());
        verify(meetingRepository).save(meeting);
    }

    @Test
    void cancelledStatusIsPersisted() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.UPCOMING
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.CANCELLED, "huy"));

        assertEquals(MeetingStatus.CANCELLED, meeting.getStatus());
        verify(meetingRepository).save(meeting);
    }

    @Test
    void statusChangeRequiresReason() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.UPCOMING
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(null, null, MeetingStatus.CANCELLED, " ")
        ));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void noOpRequestIsRejected() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(1);
        LocalDateTime endTime = startTime.plusHours(1);
        MeetingEntity meeting = meeting(startTime, endTime, MeetingStatus.UPCOMING);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(
            10L,
            new AdminMeetingOverrideRequest(startTime, endTime, MeetingStatus.UPCOMING, "same")
        ));

        assertEquals("Khong co du lieu thay doi", exception.getMessage());
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void resetMinutesMeetingNotFoundIsRejected() {
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThrows(MeetingNotFoundException.class, () -> service.resetMinutesArtifacts(10L));

        verifyNoInteractions(minutesPreconditionService, minutesJobRepository, entityManager);
    }

    @Test
    void resetMinutesRequiresCompletedMeeting() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(1).plusHours(1),
            MeetingStatus.UPCOMING
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));

        BusinessValidationException exception = assertThrows(
            BusinessValidationException.class,
            () -> service.resetMinutesArtifacts(10L)
        );

        assertEquals("Chi co the khoi phuc du lieu bien ban khi cuoc hop da ket thuc", exception.getMessage());
        verifyNoInteractions(minutesPreconditionService, minutesJobRepository, entityManager);
    }

    @Test
    void resetMinutesRequiresReadyCapture() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        when(minutesPreconditionService.requireReadyForGeneration(meeting))
            .thenThrow(new BusinessValidationException("Ban ghi am chua duoc hoan tat"));

        assertThrows(BusinessValidationException.class, () -> service.resetMinutesArtifacts(10L));

        verifyNoInteractions(minutesJobRepository, entityManager);
    }

    @Test
    void resetMinutesRejectsQueuedLatestJob() {
        assertResetRejectsActiveJob(MinutesJobStatus.QUEUED);
    }

    @Test
    void resetMinutesRejectsProcessingLatestJob() {
        assertResetRejectsActiveJob(MinutesJobStatus.PROCESSING);
    }

    @Test
    void resetMinutesAllowsFailedLatestJobAndDeletesInOrder() {
        assertResetAllowsTerminalJob(MinutesJobStatus.FAILED);
    }

    @Test
    void resetMinutesAllowsCompletedLatestJobAndDeletesInOrder() {
        assertResetAllowsTerminalJob(MinutesJobStatus.COMPLETED);
    }

    @Test
    void resetMinutesAllowsNoJobAndDeletesInOrder() {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        when(minutesJobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.empty());
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq("meetingId"), eq(10L))).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(0, 0, 0, 0);

        service.resetMinutesArtifacts(10L);

        verifyDeleteFlow();
    }

    private MeetingEntity meeting(
        LocalDateTime startTime,
        LocalDateTime endTime,
        MeetingStatus status
    ) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(10L);
        meeting.setStartTime(startTime);
        meeting.setEndTime(endTime);
        meeting.setStatus(status);
        meeting.setAccessType(vn.cusc.meetingservice.model.enums.MeetingAccessType.PUBLIC);
        var owner = new vn.cusc.meetingservice.entity.UserAccountEntity();
        owner.setId(1L);
        meeting.setCreatedByAccount(owner);
        return meeting;
    }

    private void assertResetRejectsActiveJob(MinutesJobStatus status) {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        when(minutesJobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
            .thenReturn(Optional.of(job(status)));

        BusinessValidationException exception = assertThrows(
            BusinessValidationException.class,
            () -> service.resetMinutesArtifacts(10L)
        );

        assertEquals("Khong the khoi phuc khi bien ban dang duoc xu ly", exception.getMessage());
        verifyNoInteractions(entityManager);
    }

    private void assertResetAllowsTerminalJob(MinutesJobStatus status) {
        MeetingEntity meeting = meeting(
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(1),
            MeetingStatus.COMPLETED
        );
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        when(minutesJobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
            .thenReturn(Optional.of(job(status)));
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq("meetingId"), eq(10L))).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1, 2, 3, 4);

        service.resetMinutesArtifacts(10L);

        InOrder orderedDeletes = inOrder(entityManager, nativeQuery);
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_transcript_failures"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_transcript_chunks"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_minutes"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_minutes_jobs"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        verify(meetingRepository, never()).save(any());
    }


    @Test
    void administratorCannotOverrideOrResetAnotherCreatorsPublicMeeting() {
        var meeting = meeting(LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), MeetingStatus.COMPLETED);
        meeting.getCreatedByAccount().setId(2L);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        assertThrows(vn.cusc.meetingservice.exception.MeetingAccessDeniedException.class,
            () -> service.overrideMeeting(10L, new AdminMeetingOverrideRequest(null, null, MeetingStatus.CANCELLED, "reason")));
        assertThrows(vn.cusc.meetingservice.exception.MeetingAccessDeniedException.class, () -> service.resetMinutesArtifacts(10L));
        verify(meetingRepository, never()).save(any());
        verifyNoInteractions(minutesJobRepository, minutesPreconditionService, entityManager);
    }

    @Test
    void datelessMeetingDoesNotAcceptScheduleOverridesEvenForCreator() {
        var meeting = meeting(LocalDateTime.now().minusHours(1), null, MeetingStatus.IN_PROGRESS);
        meeting.setCreationMode(vn.cusc.meetingservice.model.enums.MeetingCreationMode.INSTANT);
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
        assertThrows(BusinessValidationException.class, () -> service.overrideMeeting(10L,
            new AdminMeetingOverrideRequest(null, LocalDateTime.now().plusHours(1), null, null)));
        verify(meetingRepository, never()).save(any());
    }

    private void verifyDeleteFlow() {
        InOrder orderedDeletes = inOrder(entityManager, nativeQuery);
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_transcript_failures"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("meeting_transcript_chunks"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("DELETE FROM meeting_minutes"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        orderedDeletes.verify(entityManager).createNativeQuery(contains("DELETE FROM meeting_minutes_jobs"));
        orderedDeletes.verify(nativeQuery).setParameter("meetingId", 10L);
        orderedDeletes.verify(nativeQuery).executeUpdate();
        verify(meetingRepository, never()).save(any());
    }

    private MeetingMinutesJobEntity job(MinutesJobStatus status) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setStatus(status);
        return job;
    }

    private MeetingPrincipal principal() {
        return new MeetingPrincipal(
            1L,
            UUID.randomUUID().toString(),
            null,
            "admin@cusc.vn",
            "Admin",
            null,
            AccountStatus.ACTIVE,
            SystemRole.ADMIN,
            true,
            AuthProvider.LOCAL,
            null
        );
    }
}
