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
@Table(name = "meeting_transcript_failures")
public class MeetingTranscriptFailureEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "minutes_job_id", nullable = false)
    private MeetingMinutesJobEntity minutesJob;

    @Column(name = "stt_processing_id", nullable = false, length = 100)
    private String sttProcessingId;

    @Column(name = "failure_order", nullable = false)
    private Integer failureOrder;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "segment_id", nullable = false, length = 100)
    private String segmentId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(nullable = false)
    private Integer attempts;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String error;
}
