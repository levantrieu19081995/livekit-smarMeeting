package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;

public interface MeetingParticipantConnectionRepository extends JpaRepository<MeetingParticipantConnectionEntity, Long> {
    boolean existsByMeetingId(Long meetingId);
    List<MeetingParticipantConnectionEntity> findByParticipantId(Long participantId);
    List<MeetingParticipantConnectionEntity> findByMeetingIdAndAttendeeId(Long meetingId, String attendeeId);
    Optional<MeetingParticipantConnectionEntity> findByLiveKitIdentity(String liveKitIdentity);
    Optional<MeetingParticipantConnectionEntity> findByMeetingIdAndChatTokenHash(Long meetingId, String chatTokenHash);
}
