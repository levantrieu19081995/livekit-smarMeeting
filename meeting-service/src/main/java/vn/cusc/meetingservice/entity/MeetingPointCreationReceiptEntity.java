package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Durable request receipt survives meeting deletion without retaining any join credential. */
@Getter @Setter @Entity @Table(name = "meeting_point_creation_receipts")
public class MeetingPointCreationReceiptEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_account_id", nullable = false)
    private UserAccountEntity createdByAccount;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "meeting_id") private MeetingEntity meeting;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(nullable = false, length = 64) private String inputHash;
    @Column(nullable = false, columnDefinition = "TEXT") private String receiptJson;
}
