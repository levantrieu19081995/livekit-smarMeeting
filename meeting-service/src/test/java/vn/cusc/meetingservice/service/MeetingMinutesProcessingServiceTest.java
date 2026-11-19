package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.impl.MeetingMinutesProcessingServiceImpl;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesProcessingServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingMinutesRepository minutesRepository;
    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingMinutesAuthorizationService authorizationService;
    @Mock private MeetingMinutesPreconditionService preconditionService;

    private MeetingMinutesProcessingService service;
    private MeetingEntity meeting;
    private UserAccountEntity account;

    @BeforeEach
    void setUp() {
        service = new MeetingMinutesProcessingServiceImpl(
            meetingRepository,
            minutesRepository,
            jobRepository,
            authorizationService,
            preconditionService
        );
        meeting = new MeetingEntity();
        meeting.setId(10L);
        account = new UserAccountEntity();
        account.setId(99L);
        lenient().when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
    }

    @Test
    void generateCreatesOnlyQueuedJobAndDoesNotDuplicateActiveJob() {
        MeetingMinutesJobEntity active = job(MinutesJobStatus.QUEUED, 1);
        when(preconditionService.requireReadyForGeneration(meeting))
            .thenReturn(List.of(new MeetingCaptureSessionEntity(), new MeetingCaptureSessionEntity()));
        when(jobRepository.findFirstByMeetingIdAndStatusInOrderByAttemptDesc(10L, List.of(
            MinutesJobStatus.QUEUED,
            MinutesJobStatus.PROCESSING
        ))).thenReturn(Optional.empty(), Optional.of(active));
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.empty());
        when(authorizationService.requireCurrentAccount()).thenReturn(account);
        when(jobRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            MeetingMinutesJobEntity saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        var created = service.generate(10L);
        var repeated = service.generate(10L);

        assertEquals(MinutesJobStatus.QUEUED, created.status());
        assertEquals(MinutesProcessingStage.VALIDATING, created.stage());
        assertEquals(0, created.progressPercent());
        assertEquals(1L, repeated.jobId());
        verify(jobRepository).saveAndFlush(any());
    }

    @Test
    void generateRejectsMeetingThatAlreadyHasMinutes() {
        when(minutesRepository.existsByMeetingId(10L)).thenReturn(true);

        assertThrows(BusinessValidationException.class, () -> service.generate(10L));
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void retryKeepsHistoryAndCreatesNextAttemptOnlyAfterFailed() {
        MeetingMinutesJobEntity failed = job(MinutesJobStatus.FAILED, 2);
        when(jobRepository.findFirstByMeetingIdAndStatusInOrderByAttemptDesc(10L, List.of(
            MinutesJobStatus.QUEUED,
            MinutesJobStatus.PROCESSING
        ))).thenReturn(Optional.empty());
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.of(failed));
        when(authorizationService.requireCurrentAccount()).thenReturn(account);
        when(jobRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.retry(10L);

        assertEquals(3, response.attempt());
        assertEquals(MinutesJobStatus.QUEUED, response.status());
    }

    @Test
    void getProcessingReturnsDraftReadyMessage() {
        MeetingMinutesJobEntity job = job(MinutesJobStatus.PROCESSING, 1);
        job.setStage(MinutesProcessingStage.DRAFT_READY);
        job.setProgressPercent(80);
        job.setStartedAt(LocalDateTime.now());
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
            .thenReturn(Optional.of(job));

        var response = service.getProcessing(10L);

        assertEquals(MinutesJobStatus.PROCESSING, response.status());
        assertEquals(MinutesProcessingStage.DRAFT_READY, response.stage());
        assertEquals(80, response.progressPercent());
        assertEquals("Noi dung bien ban da san sang", response.message());
    }

    @Test
    void getProcessingReturnsGeneratingMinutesMessage() {
        MeetingMinutesJobEntity job = job(MinutesJobStatus.PROCESSING, 1);
        job.setStage(MinutesProcessingStage.GENERATING_MINUTES);
        job.setProgressPercent(80);
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
            .thenReturn(Optional.of(job));

        var response = service.getProcessing(10L);

        assertEquals(MinutesProcessingStage.GENERATING_MINUTES, response.stage());
        assertEquals(80, response.progressPercent());
        assertEquals("Dang chuan bi noi dung bien ban ban dau", response.message());
    }

    private MeetingMinutesJobEntity job(MinutesJobStatus status, int attempt) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(1L);
        job.setMeeting(meeting);
        job.setStatus(status);
        job.setStage(status == MinutesJobStatus.FAILED
            ? MinutesProcessingStage.FAILED
            : MinutesProcessingStage.VALIDATING);
        job.setProgressPercent(0);
        job.setAttempt(attempt);
        job.setRequestedByAccount(account);
        return job;
    }
}
