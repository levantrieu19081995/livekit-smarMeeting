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
import vn.cusc.meetingservice.model.enums.MeetingQaStatus;

@Getter
@Setter
@Entity
@Table(name = "meeting_qa_questions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_meeting_qa_question_id_meeting", columnNames = {"id", "meeting_id"}),
    @UniqueConstraint(name = "uk_meeting_qa_question_retry", columnNames = {"meeting_id", "participant_id", "client_question_id"})
})
public class MeetingQaQuestionEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private MeetingParticipantEntity participant;

    @Column(nullable = false, length = 36)
    private String clientQuestionId;
    @Column(nullable = false, length = 2000)
    private String content;
    @Column(nullable = false)
    private boolean anonymous;
    @Column(length = 255)
    private String authorDisplayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private MeetingQaStatus status = MeetingQaStatus.OPEN;
    private LocalDateTime answeredAt;
    @Column(length = 5000)
    private String answer;
    @Column(length = 255)
    private String answerAuthorDisplayName;
    @Column(nullable = false)
    private long upvoteCount;
    @Column(nullable = false)
    private long version;
}
