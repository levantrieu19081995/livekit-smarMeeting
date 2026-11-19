package vn.cusc.meetingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "meeting_transcript_chunks")
public class MeetingTranscriptChunkEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "minutes_job_id", nullable = false)
    private MeetingMinutesJobEntity minutesJob;

    @Column(name = "stt_processing_id", nullable = false, length = 100)
    private String sttProcessingId;

    @Column(name = "result_order", nullable = false)
    private Integer resultOrder;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "attendee_id", length = 100)
    private String attendeeId;

    @Column(name = "speaker_name", length = 255)
    private String speakerName;

    @Column(name = "segment_id", nullable = false, length = 100)
    private String segmentId;

    @Column(name = "segment_index", nullable = false)
    private Integer segmentIndex;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_start_ms", nullable = false)
    private Long chunkStartMs;

    @Column(name = "chunk_duration_ms", nullable = false)
    private Long chunkDurationMs;

    @Column(name = "segment_started_at", length = 64)
    private String segmentStartedAt;

    @Column(name = "absolute_start_time", length = 64)
    private String absoluteStartTime;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;
}
