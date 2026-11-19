package vn.cusc.meetingservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingTranscriptChunkEntity;

public interface MeetingTranscriptChunkRepository
    extends JpaRepository<MeetingTranscriptChunkEntity, Long> {

    List<MeetingTranscriptChunkEntity>
    findByMinutesJob_IdOrderByResultOrderAsc(Long minutesJobId);
}
