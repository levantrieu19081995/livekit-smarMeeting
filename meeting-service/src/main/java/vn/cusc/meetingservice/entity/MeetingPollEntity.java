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
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;

@Getter
@Setter
@Entity
@Table(name = "meeting_polls", uniqueConstraints = @UniqueConstraint(
    name = "uk_meeting_polls_id_meeting", columnNames = {"id", "meeting_id"}
))
public class MeetingPollEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MeetingPollType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingPollStatus status = MeetingPollStatus.DRAFT;

    @Column(name = "allow_change_vote", nullable = false)
    private boolean allowChangeVote;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_visibility", nullable = false, length = 30)
    private MeetingPollResultVisibility resultVisibility = MeetingPollResultVisibility.AFTER_CLOSE;

    @Column(name = "min_selections", nullable = false)
    private Integer minSelections = 1;

    @Column(name = "max_selections", nullable = false)
    private Integer maxSelections = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_account_id")
    private UserAccountEntity createdByAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_participant_id")
    private MeetingParticipantEntity createdByParticipant;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
