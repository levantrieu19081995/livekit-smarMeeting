package vn.cusc.meetingservice.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;

public interface MeetingMinutesJobRepository extends JpaRepository<MeetingMinutesJobEntity, Long> {
    Optional<MeetingMinutesJobEntity> findFirstByMeetingIdOrderByAttemptDesc(Long meetingId);
    Optional<MeetingMinutesJobEntity> findFirstByMeetingIdAndStatusInOrderByAttemptDesc(
        Long meetingId,
        Collection<MinutesJobStatus> statuses
    );
    List<MeetingMinutesJobEntity> findByMeetingIdInOrderByMeetingIdAscAttemptDesc(List<Long> meetingIds);
    Optional<MeetingMinutesJobEntity> findFirstByStatusOrderByCreatedAtAscIdAsc(MinutesJobStatus status);
    Optional<MeetingMinutesJobEntity> findFirstByStatusAndStageAndTranscriptStatusOrderByUpdatedAtAscIdAsc(
        MinutesJobStatus status,
        MinutesProcessingStage stage,
        TranscriptPersistenceStatus transcriptStatus
    );
    Optional<MeetingMinutesJobEntity> findFirstByStatusAndStageAndTranscriptStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
        MinutesJobStatus status,
        MinutesProcessingStage stage,
        TranscriptPersistenceStatus transcriptStatus,
        LocalDateTime cutoff
    );
    Optional<MeetingMinutesJobEntity> findFirstByStatusAndStageAndSttProcessingIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
        MinutesJobStatus status,
        MinutesProcessingStage stage,
        LocalDateTime cutoff
    );
    Optional<MeetingMinutesJobEntity> findFirstByStatusAndStageAndSttProcessingIdIsNullAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
        MinutesJobStatus status,
        MinutesProcessingStage stage,
        LocalDateTime cutoff
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select job
        from MeetingMinutesJobEntity job
        join fetch job.meeting
        where job.id = :jobId
    """)
    Optional<MeetingMinutesJobEntity> findByIdForUpdate(
        @Param("jobId") Long jobId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select job
        from MeetingMinutesJobEntity job
        join fetch job.meeting
        where job.sttProcessingId = :processingId
    """)
    Optional<MeetingMinutesJobEntity> findBySttProcessingIdForUpdate(
        @Param("processingId") String processingId
    );
}
