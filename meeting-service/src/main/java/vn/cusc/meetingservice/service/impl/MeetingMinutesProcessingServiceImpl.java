package vn.cusc.meetingservice.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.response.MinutesProcessingResponse;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.MinutesJobNotFoundException;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.MeetingMinutesAuthorizationService;
import vn.cusc.meetingservice.service.MeetingMinutesPreconditionService;
import vn.cusc.meetingservice.service.MeetingMinutesProcessingService;

@Service
public class MeetingMinutesProcessingServiceImpl implements MeetingMinutesProcessingService {

    private static final List<MinutesJobStatus> ACTIVE_STATUSES = List.of(
        MinutesJobStatus.QUEUED,
        MinutesJobStatus.PROCESSING
    );

    private final MeetingRepository meetingRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingMinutesAuthorizationService authorizationService;
    private final MeetingMinutesPreconditionService preconditionService;

    public MeetingMinutesProcessingServiceImpl(
        MeetingRepository meetingRepository,
        MeetingMinutesRepository minutesRepository,
        MeetingMinutesJobRepository jobRepository,
        MeetingMinutesAuthorizationService authorizationService,
        MeetingMinutesPreconditionService preconditionService
    ) {
        this.meetingRepository = meetingRepository;
        this.minutesRepository = minutesRepository;
        this.jobRepository = jobRepository;
        this.authorizationService = authorizationService;
        this.preconditionService = preconditionService;
    }

    @Override
    @Transactional
    public MinutesProcessingResponse generate(Long meetingId) {
        MeetingEntity meeting = lockAndAuthorize(meetingId);
        List<MeetingCaptureSessionEntity> captures = preconditionService.requireReadyForGeneration(meeting);
        if (minutesRepository.existsByMeetingId(meetingId)) {
            throw new BusinessValidationException("Cuoc hop da co bien ban");
        }
        return jobRepository.findFirstByMeetingIdAndStatusInOrderByAttemptDesc(meetingId, ACTIVE_STATUSES)
            .map(this::toResponse)
            .orElseGet(() -> toResponse(createJob(meeting, nextAttempt(meetingId))));
    }

    @Override
    @Transactional(readOnly = true)
    public MinutesProcessingResponse getProcessing(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);
        return jobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId)
            .map(this::toResponse)
            .orElseThrow(() -> new MinutesJobNotFoundException(meetingId));
    }

    @Override
    @Transactional
    public MinutesProcessingResponse retry(Long meetingId) {
        MeetingEntity meeting = lockAndAuthorize(meetingId);
        List<MeetingCaptureSessionEntity> captures = preconditionService.requireReadyForGeneration(meeting);
        if (minutesRepository.existsByMeetingId(meetingId)) {
            throw new BusinessValidationException("Cuoc hop da co bien ban");
        }
        if (jobRepository.findFirstByMeetingIdAndStatusInOrderByAttemptDesc(meetingId, ACTIVE_STATUSES).isPresent()) {
            throw new BusinessValidationException("Meeting dang co processing job hoat dong");
        }
        MeetingMinutesJobEntity latest = jobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId)
            .orElseThrow(() -> new MinutesJobNotFoundException(meetingId));
        if (latest.getStatus() != MinutesJobStatus.FAILED) {
            throw new BusinessValidationException("Chi co the thu lai processing job FAILED");
        }
        return toResponse(createJob(meeting, latest.getAttempt() + 1));
    }

    private MeetingEntity lockAndAuthorize(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        authorizationService.requireMeetingAccess(meeting);
        return meeting;
    }

    private int nextAttempt(Long meetingId) {
        return jobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId)
            .map(job -> job.getAttempt() + 1)
            .orElse(1);
    }

    private MeetingMinutesJobEntity createJob(MeetingEntity meeting, int attempt) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setMeeting(meeting);
        job.setStatus(MinutesJobStatus.QUEUED);
        job.setStage(MinutesProcessingStage.VALIDATING);
        job.setProgressPercent(0);
        job.setAttempt(attempt);
        job.setRequestedByAccount(authorizationService.requireCurrentAccount());
        return jobRepository.saveAndFlush(job);
    }

    private MinutesProcessingResponse toResponse(MeetingMinutesJobEntity job) {
        return new MinutesProcessingResponse(
            job.getId(),
            job.getMeeting().getId(),
            job.getStatus(),
            job.getStage(),
            job.getProgressPercent(),
            job.getAttempt(),
            message(job),
            job.getStartedAt(),
            job.getFinishedAt(),
            job.getErrorMessage(),
            job.getCreatedAt(),
            job.getUpdatedAt()
        );
    }

    private String message(MeetingMinutesJobEntity job) {
        if (job.getStatus() == MinutesJobStatus.QUEUED) {
            return "Dang cho xu ly";
        }
        if (job.getStatus() == MinutesJobStatus.FAILED) {
            return "Xu ly that bai";
        }
        return switch (job.getStage()) {
            case VALIDATING -> "Dang kiem tra du lieu";
            case PREPARING_AUDIO -> "Dang chuan bi ban ghi am";
            case TRANSCRIBING -> "Dang chuyen giong noi thanh van ban";
            case BUILDING_TRANSCRIPT -> "Dang xay dung ban ghi loi noi";
            case GENERATING_MINUTES -> "Dang chuan bi noi dung bien ban ban dau";
            case DRAFT_READY -> "Noi dung bien ban da san sang";
            case COMPLETED -> "Hoan thanh";
            case FAILED -> "Xu ly that bai";
        };
    }
}
