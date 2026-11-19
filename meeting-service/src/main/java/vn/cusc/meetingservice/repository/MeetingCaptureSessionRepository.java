package vn.cusc.meetingservice.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.model.enums.CaptureStatus;

public interface MeetingCaptureSessionRepository extends JpaRepository<MeetingCaptureSessionEntity, Long> {
    @org.springframework.data.jpa.repository.Query("select session.meeting.id from MeetingCaptureSessionEntity session where session.id = :sessionId")
    Optional<Long> findMeetingIdById(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);
    Optional<MeetingCaptureSessionEntity> findFirstByMeetingIdOrderByIdDesc(Long meetingId);
    List<MeetingCaptureSessionEntity> findAllByMeetingIdOrderByStartedAtAscIdAsc(Long meetingId);
    List<MeetingCaptureSessionEntity> findAllByMeetingIdAndStatusOrderByStartedAtAscIdAsc(Long meetingId, CaptureStatus status);
    Optional<MeetingCaptureSessionEntity> findFirstByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
        List<CaptureStatus> statuses,
        LocalDateTime updatedAt
    );
    Optional<MeetingCaptureSessionEntity> findFirstByStatusAndExternalSessionIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
        CaptureStatus status,
        LocalDateTime updatedAt
    );
}
