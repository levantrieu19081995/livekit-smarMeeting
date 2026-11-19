package vn.cusc.meetingservice.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingFileCleanupJobEntity;

public interface MeetingFileCleanupJobRepository extends JpaRepository<MeetingFileCleanupJobEntity, Long> {
    @Modifying
    @Query(value = "INSERT INTO meeting_file_cleanup_jobs (storage_path, attempts, next_attempt_at, created_at, updated_at) VALUES (:path, 0, :now, :now, :now) ON CONFLICT DO NOTHING", nativeQuery = true)
    int enqueueIfAbsent(@Param("path") String path, @Param("now") LocalDateTime now);
    Optional<MeetingFileCleanupJobEntity> findByStoragePath(String storagePath);
    @Query("select job.id from MeetingFileCleanupJobEntity job where job.nextAttemptAt <= :now and (job.leaseUntil is null or job.leaseUntil <= :now) order by job.nextAttemptAt, job.id")
    List<Long> findReadyIds(@Param("now") LocalDateTime now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from MeetingFileCleanupJobEntity job where job.id = :id")
    Optional<MeetingFileCleanupJobEntity> findByIdForUpdate(@Param("id") Long id);
}
