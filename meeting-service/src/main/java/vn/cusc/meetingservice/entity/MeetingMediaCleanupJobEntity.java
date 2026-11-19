package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "meeting_media_cleanup_jobs")
public class MeetingMediaCleanupJobEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String operationKey;
    private Long meetingId;
    private Long creatorAccountId;
    @Column(nullable = false, length = 30) private String operation;
    @Column(nullable = false, length = 255) private String roomName;
    @Column(length = 255) private String targetIdentity;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private LocalDateTime nextAttemptAt;
    @Column(length = 100) private String lastErrorCode;
}
