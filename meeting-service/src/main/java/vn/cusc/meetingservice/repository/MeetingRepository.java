package vn.cusc.meetingservice.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vn.cusc.meetingservice.entity.MeetingEntity;

public interface MeetingRepository extends JpaRepository<MeetingEntity, Long>, JpaSpecificationExecutor<MeetingEntity> {
    Optional<MeetingEntity> findByMeetingCode(String meetingCode);
    Optional<MeetingEntity> findByRoomName(String roomName);
    boolean existsByMeetingCode(String meetingCode);
    boolean existsByRoomName(String roomName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select meeting from MeetingEntity meeting where meeting.id = :meetingId")
    Optional<MeetingEntity> findByIdForUpdate(@Param("meetingId") Long meetingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select meeting from MeetingEntity meeting where meeting.roomName = :roomName")
    Optional<MeetingEntity> findByRoomNameForUpdate(@Param("roomName") String roomName);
}
