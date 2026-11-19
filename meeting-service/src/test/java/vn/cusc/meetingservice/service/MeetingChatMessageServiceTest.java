package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.dto.request.SaveMeetingChatMessageRequest;
import vn.cusc.meetingservice.entity.MeetingChatMessageEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.MeetingChatMessageType;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingChatMessageRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.service.impl.MeetingChatMessageServiceImpl;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

@ExtendWith(MockitoExtension.class)
class MeetingChatMessageServiceTest {

    @Mock private MeetingChatMessageRepository chatMessageRepository;
    @Mock private MeetingParticipantConnectionRepository connectionRepository;

    @Test
    void saveMessageUsesParticipantConnectionIdentity() {
        MeetingChatMessageService service = service();
        String rawToken = "raw-chat-token";
        MeetingEntity meeting = meeting();
        MeetingParticipantEntity participant = participant();
        MeetingParticipantConnectionEntity connection = connection(meeting, participant, rawToken);
        SaveMeetingChatMessageRequest request = new SaveMeetingChatMessageRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            "  Toi dong y phuong an 2  "
        );

        when(connectionRepository.findByMeetingIdAndChatTokenHash(
            11L,
            MeetingChatTokenUtils.sha256(rawToken)
        )).thenReturn(Optional.of(connection));
        when(chatMessageRepository.findByMeetingIdAndClientMessageId(
            11L,
            request.clientMessageId()
        )).thenReturn(Optional.empty());
        when(chatMessageRepository.save(any(MeetingChatMessageEntity.class)))
            .thenAnswer(invocation -> {
                MeetingChatMessageEntity message = invocation.getArgument(0);
                message.setId(99L);
                return message;
            });

        var response = service.saveMessage(11L, rawToken, request);

        assertEquals(99L, response.id());
        assertEquals("Nguyen Van A", response.senderDisplayName());
        assertEquals(MeetingChatMessageType.TEXT, response.messageType());
        assertEquals("Toi dong y phuong an 2", response.content());
        assertNotNull(response.sentAt());

        ArgumentCaptor<MeetingChatMessageEntity> messageCaptor =
            ArgumentCaptor.forClass(MeetingChatMessageEntity.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        MeetingChatMessageEntity message = messageCaptor.getValue();
        assertSame(meeting, message.getMeeting());
        assertSame(participant, message.getParticipant());
        assertSame(connection, message.getParticipantConnection());
        assertEquals(MeetingChatMessageType.TEXT, message.getMessageType());
        assertEquals(participant.getFullName(), message.getSenderDisplayName());
    }

    @Test
    void duplicateClientMessageReturnsExistingRecord() {
        MeetingChatMessageService service = service();
        String rawToken = "raw-chat-token";
        MeetingEntity meeting = meeting();
        MeetingParticipantEntity participant = participant();
        MeetingParticipantConnectionEntity connection = connection(meeting, participant, rawToken);
        SaveMeetingChatMessageRequest request = new SaveMeetingChatMessageRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            "Retry content"
        );
        MeetingChatMessageEntity existing = new MeetingChatMessageEntity();
        existing.setId(77L);
        existing.setClientMessageId(request.clientMessageId());
        existing.setSenderDisplayName("Nguyen Van A");
        existing.setMessageType(MeetingChatMessageType.TEXT);
        existing.setContent("Retry content");
        existing.setParticipant(participant);
        existing.setSentAt(LocalDateTime.now());

        when(connectionRepository.findByMeetingIdAndChatTokenHash(
            11L,
            MeetingChatTokenUtils.sha256(rawToken)
        )).thenReturn(Optional.of(connection));
        when(chatMessageRepository.findByMeetingIdAndClientMessageId(
            11L,
            request.clientMessageId()
        )).thenReturn(Optional.of(existing));

        var response = service.saveMessage(11L, rawToken, request);

        assertEquals(77L, response.id());
        assertEquals("Retry content", response.content());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void invalidParticipantTokenIsRejected() {
        MeetingChatMessageService service = service();
        SaveMeetingChatMessageRequest request = new SaveMeetingChatMessageRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            "Message"
        );

        when(connectionRepository.findByMeetingIdAndChatTokenHash(
            11L,
            MeetingChatTokenUtils.sha256("invalid-token")
        )).thenReturn(Optional.empty());

        assertThrows(
            MeetingAccessDeniedException.class,
            () -> service.saveMessage(11L, "invalid-token", request)
        );
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void pendingConnectionWithValidChatTokenCanPersistBeforeDelayedWebhook() {
        MeetingChatMessageService service = service();
        String rawToken = "raw-chat-token";
        MeetingParticipantConnectionEntity connection = connection(meeting(), participant(), rawToken);
        connection.setStatus(ParticipantConnectionStatus.PENDING);
        SaveMeetingChatMessageRequest request = new SaveMeetingChatMessageRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            "Message"
        );

        when(connectionRepository.findByMeetingIdAndChatTokenHash(
            11L,
            MeetingChatTokenUtils.sha256(rawToken)
        )).thenReturn(Optional.of(connection));

        when(chatMessageRepository.save(any(MeetingChatMessageEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals("Message", service.saveMessage(11L, rawToken, request).content());
        verify(chatMessageRepository).save(any());
    }

    private MeetingChatMessageService service() {
        return new MeetingChatMessageServiceImpl(
            chatMessageRepository,
            connectionRepository
        );
    }

    private MeetingEntity meeting() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(11L);
        return meeting;
    }

    private MeetingParticipantEntity participant() {
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setId(21L);
        participant.setFullName("Nguyen Van A");
        participant.setStatus(ParticipantStatus.JOINED);
        return participant;
    }

    private MeetingParticipantConnectionEntity connection(
        MeetingEntity meeting,
        MeetingParticipantEntity participant,
        String rawToken
    ) {
        MeetingParticipantConnectionEntity connection =
            new MeetingParticipantConnectionEntity();
        connection.setMeeting(meeting);
        connection.setParticipant(participant);
        connection.setStatus(ParticipantConnectionStatus.CONNECTED);
        connection.setChatTokenHash(MeetingChatTokenUtils.sha256(rawToken));
        return connection;
    }
}
