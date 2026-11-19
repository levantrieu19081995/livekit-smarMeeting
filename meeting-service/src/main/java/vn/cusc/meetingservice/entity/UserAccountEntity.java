package vn.cusc.meetingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;

@Getter
@Setter
@Entity
@Table(name = "user_accounts")
public class UserAccountEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String publicId;

    @Column(unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String normalizedEmail;

    @Column(nullable = false, length = 255)
    private String fullName;

    @Column(length = 255)
    private String passwordHash;

    @Column(length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus accountStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SystemRole systemRole;

    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false)
    private long securityVersion;
    @jakarta.persistence.Version
    @Column(nullable = false)
    private long rowVersion;

    private LocalDateTime lastLoginAt;
}
