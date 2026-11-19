package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import vn.cusc.meetingservice.entity.MeetingChatMessageEntity;

public interface MeetingChatMessageRepository
    extends JpaRepository<MeetingChatMessageEntity, Long> {

    org.springframework.data.domain.Page<MeetingChatMessageEntity> findByMeetingIdOrderBySentAtAscIdAsc(Long meetingId, org.springframework.data.domain.Pageable pageable);
    @EntityGraph(attributePaths = {"participantConnection"})
    org.springframework.data.domain.Slice<MeetingChatMessageEntity> findByMeetingIdAndIdGreaterThanOrderByIdAsc(Long meetingId, Long id, org.springframework.data.domain.Pageable pageable);
    @EntityGraph(attributePaths = {"participantConnection"})
    org.springframework.data.domain.Slice<MeetingChatMessageEntity> findByMeetingIdOrderByIdDesc(Long meetingId, org.springframework.data.domain.Pageable pageable);
    Optional<MeetingChatMessageEntity>
    findByMeetingIdAndClientMessageId(
        Long meetingId,
        String clientMessageId
    );

    @EntityGraph(attributePaths = {"participantConnection"})
    List<MeetingChatMessageEntity>
    findByMeetingIdOrderBySentAtAscIdAsc(Long meetingId);
}
