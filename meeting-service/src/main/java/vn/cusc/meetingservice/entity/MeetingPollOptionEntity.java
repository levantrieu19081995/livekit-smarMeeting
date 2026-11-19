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
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;

@Getter
@Setter
@Entity
@Table(name = "meeting_poll_options", uniqueConstraints = {
    @UniqueConstraint(name = "uk_meeting_poll_options_id_poll", columnNames = {"id", "poll_id"}),
    @UniqueConstraint(name = "uk_meeting_poll_options_order", columnNames = {"poll_id", "sort_order"}),
    @UniqueConstraint(name = "uk_meeting_poll_options_code", columnNames = {"poll_id", "code"})
})
public class MeetingPollOptionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private MeetingPollEntity poll;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MeetingPollOptionCode code;

    @Column(nullable = false, length = 255)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
