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
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;

@Getter
@Setter
@Entity
@Table(name = "meeting_participant_connections")
public class MeetingParticipantConnectionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private MeetingParticipantEntity participant;

    @Column(nullable = false, length = 100)
    private String attendeeId;

    @Column(nullable = false, length = 100)
    private String connectionId;

    @Column(nullable = false, unique = true, length = 255)
    private String liveKitIdentity;

    private LocalDateTime joinedAt;

    private LocalDateTime leftAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipantConnectionStatus status;

    @Column(name = "chat_token_hash", length = 64)
    private String chatTokenHash;
    private Long accountSecurityVersion;
    private Long lastWebhookAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "point_id")
    private MeetingPointEntity point;
}
