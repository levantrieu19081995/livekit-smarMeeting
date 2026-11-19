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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.CaptureStatus;

@Getter
@Setter
@Entity
@Table(name = "meeting_capture_sessions")
public class MeetingCaptureSessionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @Column(name = "external_session_id", length = 100)
    private String externalSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaptureStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "stopping_at")
    private LocalDateTime stoppingAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "manifest_path", length = 1000)
    private String manifestPath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
