package vn.cusc.meetingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;

@Getter
@Setter
@Entity
@Table(
    name = "meeting_minutes_jobs",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_meeting_minutes_jobs_attempt",
        columnNames = {"meeting_id", "attempt"}
    )
)
public class MeetingMinutesJobEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MinutesJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MinutesProcessingStage stage;

    @Column(nullable = false)
    private Integer progressPercent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_account_id", nullable = false)
    private UserAccountEntity requestedByAccount;

    @Column(nullable = false)
    private Integer attempt;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(
        name = "stt_processing_id",
        length = 100
    )
    private String sttProcessingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stt_status", length = 20)
    private SttTerminalStatus sttStatus;

    @Column(name = "stt_total_manifests")
    private Integer sttTotalManifests;

    @Column(name = "stt_total_segments")
    private Integer sttTotalSegments;

    @Column(name = "stt_total_chunks")
    private Integer sttTotalChunks;

    @Column(name = "stt_processed_chunks")
    private Integer sttProcessedChunks;

    @Column(name = "stt_succeeded_chunks")
    private Integer sttSucceededChunks;

    @Column(name = "stt_failed_chunks")
    private Integer sttFailedChunks;

    @Column(name = "stt_error_message", columnDefinition = "TEXT")
    private String sttErrorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "transcript_status", length = 20)
    private TranscriptPersistenceStatus transcriptStatus;

    @Column(name = "transcript_attempts", nullable = false)
    private Integer transcriptAttempts = 0;

    @Column(name = "transcript_last_error", columnDefinition = "TEXT")
    private String transcriptLastError;

    @Column(name = "transcript_persisted_at")
    private LocalDateTime transcriptPersistedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
