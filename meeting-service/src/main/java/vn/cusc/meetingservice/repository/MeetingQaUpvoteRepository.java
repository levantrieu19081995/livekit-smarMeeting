package vn.cusc.meetingservice.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingQaUpvoteEntity;

public interface MeetingQaUpvoteRepository extends JpaRepository<MeetingQaUpvoteEntity, Long> {
    Optional<MeetingQaUpvoteEntity> findByQuestionIdAndParticipantId(Long questionId, Long participantId);

    @Query("select vote.question.id from MeetingQaUpvoteEntity vote where vote.participant.id = :participantId and vote.question.id in :ids")
    List<Long> findVotedQuestionIds(@Param("participantId") Long participantId, @Param("ids") Collection<Long> ids);
}
