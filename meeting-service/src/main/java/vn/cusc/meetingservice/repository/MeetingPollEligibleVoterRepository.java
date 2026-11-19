package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingPollEligibleVoterEntity;

public interface MeetingPollEligibleVoterRepository extends JpaRepository<MeetingPollEligibleVoterEntity, Long> {
    List<MeetingPollEligibleVoterEntity> findByPollIdOrderByIdAsc(Long pollId);
    Optional<MeetingPollEligibleVoterEntity> findByPollIdAndParticipantId(Long pollId, Long participantId);
    boolean existsByPollIdAndParticipantIdAndSnapshotAtIsNotNull(Long pollId, Long participantId);
    long countByPollIdAndSnapshotAtIsNotNull(Long pollId);
}
