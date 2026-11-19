package vn.cusc.meetingservice.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "meeting_file_cleanup_jobs")
public class MeetingFileCleanupJobEntity extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 500) private String storagePath;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private LocalDateTime nextAttemptAt;
    @Column(length = 100) private String lastErrorCode;
    @Column(length = 36) private String leaseToken;
    private LocalDateTime leaseUntil;
}
