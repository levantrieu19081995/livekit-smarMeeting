package vn.cusc.meetingservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingPollBallotSelectionEntity;

public interface MeetingPollBallotSelectionRepository extends JpaRepository<MeetingPollBallotSelectionEntity, Long> {
    List<MeetingPollBallotSelectionEntity> findByBallotIdOrderByOptionSortOrderAscOptionIdAsc(Long ballotId);

    @Query("select s.option.id as optionId, count(s.id) as voteCount from MeetingPollBallotSelectionEntity s "
        + "where s.poll.id = :pollId group by s.option.id")
    List<OptionVoteCount> countOptionsByPollId(@Param("pollId") Long pollId);

    interface OptionVoteCount {
        Long getOptionId();
        long getVoteCount();
    }
}
