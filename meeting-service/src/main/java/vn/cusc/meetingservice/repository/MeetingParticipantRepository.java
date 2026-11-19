package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipantEntity, Long> {
    List<MeetingParticipantEntity> findByMeetingId(Long meetingId);
    Optional<MeetingParticipantEntity> findByMeetingIdAndUserId(Long meetingId, String userId);
    Optional<MeetingParticipantEntity> findByMeetingIdAndUserAccountId(Long meetingId, Long userAccountId);
    List<MeetingParticipantEntity> findByInvitedEmailNormalized(String invitedEmailNormalized);
    boolean existsByMeetingIdAndUserId(Long meetingId, String userId);
    List<MeetingParticipantEntity> findByMeetingIdIn(List<Long> meetingIds);
}
