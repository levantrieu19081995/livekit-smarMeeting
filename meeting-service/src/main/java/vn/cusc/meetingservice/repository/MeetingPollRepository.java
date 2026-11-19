package vn.cusc.meetingservice.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.MeetingPollEntity;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;

public interface MeetingPollRepository extends JpaRepository<MeetingPollEntity, Long> {
    Page<MeetingPollEntity> findByMeetingIdOrderByCreatedAtDescIdDesc(Long meetingId, Pageable pageable);
    Page<MeetingPollEntity> findByMeetingIdAndStatusNotOrderByCreatedAtDescIdDesc(Long meetingId, MeetingPollStatus status, Pageable pageable);
    Optional<MeetingPollEntity> findByIdAndMeetingId(Long pollId, Long meetingId);
    @Query("select poll from MeetingPollEntity poll where poll.meeting.id = :meetingId "
        + "and poll.status = vn.cusc.meetingservice.model.enums.MeetingPollStatus.OPEN")
    Optional<MeetingPollEntity> findOpenByMeetingId(@Param("meetingId") Long meetingId);

    // Lifecycle/vote services must lock Meeting first, then Poll in this order.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select poll from MeetingPollEntity poll where poll.id = :pollId and poll.meeting.id = :meetingId")
    Optional<MeetingPollEntity> findByIdAndMeetingIdForUpdate(
        @Param("pollId") Long pollId,
        @Param("meetingId") Long meetingId
    );
}
