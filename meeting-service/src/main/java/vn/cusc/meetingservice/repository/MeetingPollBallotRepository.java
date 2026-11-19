package vn.cusc.meetingservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingPollBallotEntity;

public interface MeetingPollBallotRepository extends JpaRepository<MeetingPollBallotEntity, Long> {
    Optional<MeetingPollBallotEntity> findByPollIdAndVoterId(Long pollId, Long voterId);
    long countByPollId(Long pollId);
}
