package vn.cusc.meetingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingTranscriptFailureEntity;

public interface MeetingTranscriptFailureRepository
    extends JpaRepository<MeetingTranscriptFailureEntity, Long> {
}
