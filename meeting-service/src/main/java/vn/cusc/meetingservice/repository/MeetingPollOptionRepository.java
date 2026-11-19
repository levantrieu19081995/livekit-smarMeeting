package vn.cusc.meetingservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingPollOptionEntity;

public interface MeetingPollOptionRepository extends JpaRepository<MeetingPollOptionEntity, Long> {
    List<MeetingPollOptionEntity> findByPollIdOrderBySortOrderAscIdAsc(Long pollId);
}
