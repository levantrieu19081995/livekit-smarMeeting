package vn.cusc.meetingservice.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingConclusionEntity;

public interface MeetingConclusionRepository extends JpaRepository<MeetingConclusionEntity, Long> {
    Optional<MeetingConclusionEntity> findByMeetingId(Long meetingId);
    @Query("select c.meeting.id from MeetingConclusionEntity c where c.status = 'GENERATING' "
        + "and c.availableAt <= :now and (c.leaseUntil is null or c.leaseUntil <= :now) order by c.availableAt, c.id")
    List<Long> due(@Param("now") LocalDateTime now, Pageable limit);
}
