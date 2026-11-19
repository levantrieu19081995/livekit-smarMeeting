package vn.cusc.meetingservice.service;

import org.springframework.stereotype.Service;
import java.util.List;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;

@Service
public class MeetingMinutesPreconditionService {

    private final MeetingCaptureSessionRepository captureRepository;

    public MeetingMinutesPreconditionService(
        MeetingCaptureSessionRepository captureRepository
    ) {
        this.captureRepository = captureRepository;
    }

    public boolean isMeetingFinishedForMinutes(MeetingEntity meeting) {
        return meeting.getStatus() == MeetingStatus.COMPLETED;
    }

    public List<MeetingCaptureSessionEntity> requireReadyForGeneration(MeetingEntity meeting) {
        if (!isMeetingFinishedForMinutes(meeting)) {
            throw new BusinessValidationException("Cuoc hop chua ket thuc");
        }
        List<MeetingCaptureSessionEntity> allCaptures = captureRepository
            .findAllByMeetingIdOrderByStartedAtAscIdAsc(meeting.getId());
        if (allCaptures.stream().anyMatch(this::isActiveCapture)) {
            throw new BusinessValidationException("Ban ghi am chua duoc hoan tat");
        }
        MeetingCaptureSessionEntity latest = captureRepository
            .findFirstByMeetingIdOrderByIdDesc(meeting.getId())
            .orElse(null);
        if (latest != null) {
            if (latest.getStatus() == CaptureStatus.FAILED
                || (latest.getStatus() == CaptureStatus.COMPLETED && isBlank(latest.getManifestPath()))) {
                throw new BusinessValidationException("Ban ghi am chua duoc hoan tat");
            }
        }
        List<MeetingCaptureSessionEntity> captures = allCaptures.stream()
            .filter(capture -> capture.getStatus() == CaptureStatus.COMPLETED)
            .filter(capture -> !isBlank(capture.getManifestPath()))
            .toList();
        if (captures.isEmpty()) {
            throw new BusinessValidationException("Ban ghi am chua duoc hoan tat");
        }
        return captures;
    }

    private boolean isActiveCapture(MeetingCaptureSessionEntity capture) {
        return capture.getStatus() == CaptureStatus.STARTING
            || capture.getStatus() == CaptureStatus.RUNNING
            || capture.getStatus() == CaptureStatus.STOPPING;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
