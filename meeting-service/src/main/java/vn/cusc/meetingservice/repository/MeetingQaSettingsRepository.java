package vn.cusc.meetingservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingQaSettingsEntity;

public interface MeetingQaSettingsRepository extends JpaRepository<MeetingQaSettingsEntity, Long> {
    Optional<MeetingQaSettingsEntity> findByMeetingId(Long meetingId);
}
