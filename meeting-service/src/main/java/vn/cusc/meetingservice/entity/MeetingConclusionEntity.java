package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "meeting_conclusions", uniqueConstraints = @UniqueConstraint(columnNames = "meeting_id"))
public class MeetingConclusionEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, length = 20) private String stage;
    @Column(nullable = false, unique = true, length = 36) private String generationId;
    @Column(nullable = false) private Integer sourceMinutesVersion;
    @Column(nullable = false, columnDefinition = "TEXT") private String manualContentBeforeAi;
    @Column(nullable = false, columnDefinition = "TEXT") private String sourceManualContent;
    @Column(nullable = false, columnDefinition = "TEXT") private String contextJson;
    @Column(columnDefinition = "TEXT") private String aiGeneratedContent;
    @Column(columnDefinition = "TEXT") private String finalContent;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "generated_by_account_id")
    private UserAccountEntity generatedByAccount;
    private LocalDateTime generatedAt;
    @Column(length = 80) private String errorCode;
    @Column(length = 500) private String errorMessage;
    @Column(nullable = false) private long revision;
    @Column(nullable = false) private int deliveryAttempts;
    @Column(nullable = false) private LocalDateTime availableAt;
    @Column(length = 36) private String leaseToken;
    private LocalDateTime leaseUntil;
}
