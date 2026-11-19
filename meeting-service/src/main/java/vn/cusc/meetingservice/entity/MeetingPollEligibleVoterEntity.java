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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MeetingRole;

@Getter
@Setter
@Entity
@Table(name = "meeting_poll_eligible_voters", uniqueConstraints = @UniqueConstraint(
    name = "uk_meeting_poll_eligible_voter", columnNames = {"poll_id", "participant_id"}
))
public class MeetingPollEligibleVoterEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private MeetingPollEntity poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private MeetingParticipantEntity participant;

    @Column(name = "participant_key_snapshot", length = 36)
    private String participantKeySnapshot;

    @Column(name = "full_name_snapshot", length = 255)
    private String fullNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_snapshot", length = 20)
    private MeetingRole roleSnapshot;

    @Column(name = "snapshot_at")
    private LocalDateTime snapshotAt;
}
