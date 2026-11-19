package vn.cusc.meetingservice.repository;

import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingQaQuestionEntity;
import vn.cusc.meetingservice.model.enums.MeetingQaStatus;

public interface MeetingQaQuestionRepository extends JpaRepository<MeetingQaQuestionEntity, Long>,
    JpaSpecificationExecutor<MeetingQaQuestionEntity> {
    org.springframework.data.domain.Page<MeetingQaQuestionEntity> findByMeetingIdAndStatusInOrderByCreatedAtAscIdAsc(Long meetingId, java.util.List<MeetingQaStatus> statuses, Pageable pageable);
    Optional<MeetingQaQuestionEntity> findByMeetingIdAndId(Long meetingId, Long id);
    Optional<MeetingQaQuestionEntity> findByMeetingIdAndParticipantIdAndClientQuestionId(
        Long meetingId, Long participantId, String clientQuestionId);
    interface ReportRow {
        String getContent();
        boolean getAnonymous();
        String getAuthorDisplayName();
        MeetingQaStatus getStatus();
        long getUpvoteCount();
        LocalDateTime getCreatedAt();
        LocalDateTime getAnsweredAt();
        String getAnswer();
        String getAnswerAuthorDisplayName();
    }

    @Query("""
        select q.content as content, q.anonymous as anonymous, q.authorDisplayName as authorDisplayName,
            q.status as status, q.upvoteCount as upvoteCount, q.createdAt as createdAt, q.answeredAt as answeredAt,
            q.answer as answer, q.answerAuthorDisplayName as answerAuthorDisplayName
        from MeetingQaQuestionEntity q where q.meeting.id = :meetingId order by q.createdAt asc, q.id asc
        """)
    Slice<ReportRow> findReportRows(@Param("meetingId") Long meetingId, Pageable pageable);
}
