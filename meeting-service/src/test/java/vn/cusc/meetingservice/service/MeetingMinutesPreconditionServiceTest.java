package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesPreconditionServiceTest {

    @Mock private MeetingCaptureSessionRepository captureRepository;

    private MeetingMinutesPreconditionService service;
    private MeetingEntity meeting;

    @BeforeEach
    void setUp() {
        service = new MeetingMinutesPreconditionService(captureRepository);
        meeting = new MeetingEntity();
        meeting.setId(10L);
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting.setStartTime(LocalDateTime.now().minusHours(2));
        meeting.setEndTime(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void persistedInProgressRejectedEvenWhenEndTimeIsPast() {
        meeting.setStatus(MeetingStatus.IN_PROGRESS);

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> service.requireReadyForGeneration(meeting));

        assertEquals("Cuoc hop chua ket thuc", exception.getMessage());
    }

    @Test
    void persistedCompletedWithValidCompletedManifestIsAccepted() {
        MeetingCaptureSessionEntity capture = capture(CaptureStatus.COMPLETED, "manifest-1.json");
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L)).thenReturn(List.of(capture));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(capture));

        List<MeetingCaptureSessionEntity> captures = service.requireReadyForGeneration(meeting);

        assertEquals(List.of(capture), captures);
    }

    @Test
    void newerRunningCaptureBlocksOldCompletedManifest() {
        assertCaptureTimelineRejected(
            capture(CaptureStatus.COMPLETED, "manifest-1.json"),
            capture(CaptureStatus.RUNNING, null)
        );
    }

    @Test
    void newerStartingCaptureBlocksOldCompletedManifest() {
        assertCaptureTimelineRejected(
            capture(CaptureStatus.COMPLETED, "manifest-1.json"),
            capture(CaptureStatus.STARTING, null)
        );
    }

    @Test
    void newerStoppingCaptureBlocksOldCompletedManifest() {
        assertCaptureTimelineRejected(
            capture(CaptureStatus.COMPLETED, "manifest-1.json"),
            capture(CaptureStatus.STOPPING, null)
        );
    }

    @Test
    void newerFailedCaptureByIdBlocksOlderCompletedManifest() {
        MeetingCaptureSessionEntity olderCompleted = capture(10L, CaptureStatus.COMPLETED, "manifest-1.json", LocalDateTime.now().minusMinutes(20));
        MeetingCaptureSessionEntity newerFailed = capture(11L, CaptureStatus.FAILED, null, null);
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L))
            .thenReturn(List.of(olderCompleted, newerFailed));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(newerFailed));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> service.requireReadyForGeneration(meeting));

        assertEquals("Ban ghi am chua duoc hoan tat", exception.getMessage());
    }

    @Test
    void oldFailedWithNullStartedAtDoesNotBlockNewerCompletedCapture() {
        MeetingCaptureSessionEntity oldFailed = capture(10L, CaptureStatus.FAILED, null, null);
        MeetingCaptureSessionEntity newerCompleted = capture(11L, CaptureStatus.COMPLETED, "manifest-2.json", LocalDateTime.now());
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L))
            .thenReturn(List.of(newerCompleted, oldFailed));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(newerCompleted));

        List<MeetingCaptureSessionEntity> captures = service.requireReadyForGeneration(meeting);

        assertEquals(List.of(newerCompleted), captures);
    }

    @Test
    void newerCompletedMissingManifestBlocksOldCompletedManifestFallback() {
        MeetingCaptureSessionEntity olderCompleted = capture(10L, CaptureStatus.COMPLETED, "manifest-1.json", LocalDateTime.now().minusMinutes(20));
        MeetingCaptureSessionEntity newerCompletedMissingManifest = capture(11L, CaptureStatus.COMPLETED, null, LocalDateTime.now());
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L))
            .thenReturn(List.of(olderCompleted, newerCompletedMissingManifest));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(newerCompletedMissingManifest));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> service.requireReadyForGeneration(meeting));

        assertEquals("Ban ghi am chua duoc hoan tat", exception.getMessage());
    }

    @Test
    void multipleValidCompletedSessionsReturnedChronologically() {
        MeetingCaptureSessionEntity first = capture(CaptureStatus.COMPLETED, "manifest-1.json");
        MeetingCaptureSessionEntity second = capture(CaptureStatus.COMPLETED, "manifest-2.json");
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L))
            .thenReturn(List.of(first, second));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(second));

        List<MeetingCaptureSessionEntity> captures = service.requireReadyForGeneration(meeting);

        assertEquals(List.of(first, second), captures);
    }

    @Test
    void noCompletedManifestIsRejected() {
        MeetingCaptureSessionEntity notStarted = capture(CaptureStatus.NOT_STARTED, null);
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L)).thenReturn(List.of(notStarted));
        when(captureRepository.findFirstByMeetingIdOrderByIdDesc(10L)).thenReturn(Optional.of(notStarted));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> service.requireReadyForGeneration(meeting));

        assertEquals("Ban ghi am chua duoc hoan tat", exception.getMessage());
    }

    private void assertCaptureTimelineRejected(MeetingCaptureSessionEntity... captures) {
        when(captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(10L)).thenReturn(List.of(captures));

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> service.requireReadyForGeneration(meeting));

        assertEquals("Ban ghi am chua duoc hoan tat", exception.getMessage());
    }

    private MeetingCaptureSessionEntity capture(CaptureStatus status, String manifestPath) {
        return capture(null, status, manifestPath, null);
    }

    private MeetingCaptureSessionEntity capture(
        Long id,
        CaptureStatus status,
        String manifestPath,
        LocalDateTime startedAt
    ) {
        MeetingCaptureSessionEntity capture = new MeetingCaptureSessionEntity();
        capture.setId(id);
        capture.setStatus(status);
        capture.setManifestPath(manifestPath);
        capture.setStartedAt(startedAt);
        return capture;
    }
}
