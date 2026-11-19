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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import vn.cusc.meetingservice.model.enums.MeetingChatMessageType;

@Getter
@Setter
@Entity
@Table(name = "meeting_chat_messages")
public class MeetingChatMessageEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingEntity meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id")
    private MeetingParticipantEntity participant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_connection_id")
    private MeetingParticipantConnectionEntity participantConnection;

    @Column(
        name = "client_message_id",
        nullable = false,
        length = 36
    )
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "message_type",
        nullable = false,
        length = 20
    )
    private MeetingChatMessageType messageType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(
        name = "sender_display_name",
        nullable = false,
        length = 255
    )
    private String senderDisplayName;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
