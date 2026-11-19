package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MeetingSpeakingStatus;

@Getter @Setter @Entity @Table(name = "meeting_speaking_requests")
public class MeetingSpeakingRequestEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false)
    private MeetingParticipantEntity participant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_connection_id", nullable = false)
    private MeetingParticipantConnectionEntity participantConnection;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private MeetingPointEntity point;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(length = 255) private String submittedSpeakerName;
    @Column(nullable = false, length = 255) private String speakerName;
    @Column(nullable = false, length = 510) private String speakerKey;
    @Column(nullable = false, length = 150) private String pointName;
    @Column(nullable = false, length = 255) private String requestedByDisplayName;
    @Column(length = 255) private String approvedByDisplayName;
    @Column(length = 255) private String finishedByDisplayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MeetingSpeakingStatus status;
    @Column(nullable = false) private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime finishedAt;
    @Column(nullable = false) private long version;
}
