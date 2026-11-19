package vn.cusc.meetingservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingDocumentEntity;

public interface MeetingDocumentRepository extends JpaRepository<MeetingDocumentEntity, Long> {
    List<MeetingDocumentEntity> findByMeetingId(Long meetingId);
    Optional<MeetingDocumentEntity> findByIdAndMeetingId(Long documentId, Long meetingId);
    boolean existsByStoredFileName(String storedFileName);
    Optional<MeetingDocumentEntity> findByMeetingIdAndContentSha256AndOriginalFileName(Long meetingId, String contentSha256, String originalFileName);
}
