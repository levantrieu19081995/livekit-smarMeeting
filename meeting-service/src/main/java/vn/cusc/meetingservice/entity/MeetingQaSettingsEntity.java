package vn.cusc.meetingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "meeting_qa_settings", uniqueConstraints = @UniqueConstraint(
    name = "uk_meeting_qa_settings_meeting", columnNames = "meeting_id"))
public class MeetingQaSettingsEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false)
    private boolean anonymousAllowed = true;
    @Column(nullable = false)
    private boolean moderationEnabled;
    @Column(nullable = false)
    private long version;
}
