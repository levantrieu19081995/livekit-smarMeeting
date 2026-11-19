package vn.cusc.meetingservice.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingMediaCleanupJobEntity;

public interface MeetingMediaCleanupJobRepository extends JpaRepository<MeetingMediaCleanupJobEntity, Long> {
    Optional<MeetingMediaCleanupJobEntity> findFirstByNextAttemptAtLessThanEqualOrderByIdAsc(LocalDateTime now);
    boolean existsByMeetingId(Long meetingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from MeetingMediaCleanupJobEntity job where job.id = :id")
    Optional<MeetingMediaCleanupJobEntity> findByIdForUpdate(@Param("id") Long id);
}
