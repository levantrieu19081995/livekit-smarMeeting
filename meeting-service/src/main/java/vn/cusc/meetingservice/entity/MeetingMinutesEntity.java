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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;

@Getter
@Setter
@Entity
@Table(
    name = "meeting_minutes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_meeting_minutes_meeting_version",
        columnNames = {"meeting_id", "version"}
    )
)
public class MeetingMinutesEntity extends BaseEntity {
    @jakarta.persistence.Version
    @Column(nullable = false)
    private Long revision = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MinutesStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MinutesVersionSource source;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawTranscript;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String minutesJson;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_account_id")
    private UserAccountEntity createdByAccount;

    private LocalDateTime finalizedAt;
}
