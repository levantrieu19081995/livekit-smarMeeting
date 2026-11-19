package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import vn.cusc.meetingservice.entity.MeetingSpeakingRequestEntity;
import vn.cusc.meetingservice.model.enums.MeetingSpeakingStatus;

public interface MeetingSpeakingRequestRepository extends JpaRepository<MeetingSpeakingRequestEntity, Long> {
    Optional<MeetingSpeakingRequestEntity> findByMeetingIdAndId(Long meetingId, Long id);
    Optional<MeetingSpeakingRequestEntity> findByMeetingIdAndParticipantIdAndRequestId(Long meetingId, Long participantId, String requestId);
    boolean existsByMeetingIdAndParticipantIdAndPointIdAndSpeakerKeyAndStatusIn(Long meetingId, Long participantId,
        Long pointId, String speakerKey, List<MeetingSpeakingStatus> statuses);
    @EntityGraph(attributePaths = {"meeting", "participant", "participant.userAccount", "participantConnection", "point"})
    Page<MeetingSpeakingRequestEntity> findByMeetingId(Long meetingId, Pageable pageable);
    List<MeetingSpeakingRequestEntity> findByMeetingIdAndStatusIn(Long meetingId, List<MeetingSpeakingStatus> statuses);
    List<MeetingSpeakingRequestEntity> findByMeetingIdAndParticipantIdAndStatusIn(Long meetingId, Long participantId,
        List<MeetingSpeakingStatus> statuses);
    @Query("select distinct r.meeting.id from MeetingSpeakingRequestEntity r where r.status in :statuses "
        + "and (r.meeting.status in (vn.cusc.meetingservice.model.enums.MeetingStatus.COMPLETED, "
        + "vn.cusc.meetingservice.model.enums.MeetingStatus.CANCELLED) or r.meeting.endTime < :now)")
    List<Long> findTerminalMeetingIds(List<MeetingSpeakingStatus> statuses, LocalDateTime now, Pageable pageable);
}
