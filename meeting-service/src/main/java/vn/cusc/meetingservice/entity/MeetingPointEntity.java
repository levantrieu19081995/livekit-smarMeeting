package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "meeting_points")
public class MeetingPointEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, length = 300)
    private String nameKey;
    @Column(nullable = false)
    private int sortOrder;
}
