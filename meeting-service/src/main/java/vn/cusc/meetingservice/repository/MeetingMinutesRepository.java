package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;

public interface MeetingMinutesRepository extends JpaRepository<MeetingMinutesEntity, Long> {
    List<MeetingMinutesEntity> findByMeetingIdOrderByVersionDesc(Long meetingId);
    Optional<MeetingMinutesEntity> findFirstByMeetingIdOrderByVersionDesc(Long meetingId);
    Optional<MeetingMinutesEntity> findByMeetingIdAndVersion(Long meetingId, Integer version);
    List<MeetingMinutesEntity> findByMeetingIdInOrderByMeetingIdAscVersionDesc(List<Long> meetingIds);
    boolean existsByMeetingId(Long meetingId);
}
