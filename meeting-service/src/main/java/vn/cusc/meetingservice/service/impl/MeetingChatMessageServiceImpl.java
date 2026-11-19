package vn.cusc.meetingservice.service.impl;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.request.SaveMeetingChatMessageRequest;
import vn.cusc.meetingservice.dto.response.MeetingChatMessageResponse;
import vn.cusc.meetingservice.entity.MeetingChatMessageEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.MeetingChatMessageType;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingChatMessageRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.service.MeetingChatMessageService;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

@Service
public class MeetingChatMessageServiceImpl implements MeetingChatMessageService {

    private final MeetingChatMessageRepository chatMessageRepository;
    private final MeetingParticipantConnectionRepository connectionRepository;
    private vn.cusc.meetingservice.repository.MeetingRepository meetings;
    private vn.cusc.meetingservice.security.MeetingParticipantCredentialValidator credentials;
    @org.springframework.beans.factory.annotation.Autowired public void setMeetings(vn.cusc.meetingservice.repository.MeetingRepository repository) { meetings = repository; }
    @org.springframework.beans.factory.annotation.Autowired public void setCredentials(vn.cusc.meetingservice.security.MeetingParticipantCredentialValidator validator) { credentials = validator; }

    public MeetingChatMessageServiceImpl(
        MeetingChatMessageRepository chatMessageRepository,
        MeetingParticipantConnectionRepository connectionRepository
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.connectionRepository = connectionRepository;
    }

    @Override
    @Transactional
    public MeetingChatMessageResponse saveMessage(
        Long meetingId,
        String participantToken,
        SaveMeetingChatMessageRequest request
    ) {
        if (meetings != null) meetings.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new vn.cusc.meetingservice.exception.MeetingNotFoundException(meetingId));
        if (participantToken == null || participantToken.isBlank()) {
            throw new MeetingAccessDeniedException("PARTICIPANT_TOKEN_REQUIRED");
        }

        String chatTokenHash = MeetingChatTokenUtils.sha256(participantToken);
        MeetingParticipantConnectionEntity connection =
            connectionRepository.findByMeetingIdAndChatTokenHash(
                meetingId,
                chatTokenHash
            ).orElseThrow(() ->
                new MeetingAccessDeniedException("INVALID_PARTICIPANT_TOKEN")
            );

        if (connection.getStatus() != ParticipantConnectionStatus.CONNECTED && connection.getStatus() != ParticipantConnectionStatus.PENDING) {
            throw new MeetingAccessDeniedException("PARTICIPANT_CONNECTION_NOT_CONNECTED");
        }

        MeetingParticipantEntity participant = connection.getParticipant();
        if (participant == null || participant.getStatus() == ParticipantStatus.REMOVED) {
            throw new MeetingAccessDeniedException("PARTICIPANT_REMOVED");
        }
        if (credentials != null) credentials.validate(connection);
        var meetingStatus = new vn.cusc.meetingservice.util.MeetingStatusResolver().resolve(connection.getMeeting());
        if (meetingStatus == vn.cusc.meetingservice.model.enums.MeetingStatus.COMPLETED || meetingStatus == vn.cusc.meetingservice.model.enums.MeetingStatus.CANCELLED)
            throw new vn.cusc.meetingservice.exception.BusinessValidationException("Cuộc họp đã kết thúc. Chat chỉ cho xem.");

        Optional<MeetingChatMessageEntity> existingMessage =
            chatMessageRepository.findByMeetingIdAndClientMessageId(
                meetingId,
                request.clientMessageId()
            );

        if (existingMessage.isPresent()) {
            var saved = existingMessage.get();
            if (saved.getParticipant() == null || !java.util.Objects.equals(saved.getParticipant().getId(), participant.getId()))
                throw new MeetingAccessDeniedException("CHAT_MESSAGE_OWNER_MISMATCH");
            if (!request.content().trim().equals(saved.getContent()))
                throw new vn.cusc.meetingservice.exception.BusinessValidationException("Mã tin nhắn đã được dùng cho nội dung khác.");
            return toResponse(saved);
        }

        MeetingChatMessageEntity message = new MeetingChatMessageEntity();
        message.setMeeting(connection.getMeeting());
        message.setParticipant(participant);
        message.setParticipantConnection(connection);

        message.setClientMessageId(
            request.clientMessageId()
        );

        message.setMessageType(
            MeetingChatMessageType.TEXT
        );

        message.setContent(
            request.content().trim()
        );

        message.setSenderDisplayName(
            participant.getFullName()
        );

        message.setSentAt(
            LocalDateTime.now()
        );

        return toResponse(
            chatMessageRepository.save(message)
        );
    }

    @Override @Transactional(readOnly = true)
    public vn.cusc.meetingservice.dto.response.MeetingChatPageResponse listMessages(Long meetingId, String token, long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100) throw new vn.cusc.meetingservice.exception.BusinessValidationException("Phân trang chat không hợp lệ.");
        if (token == null || token.isBlank()) throw new MeetingAccessDeniedException("PARTICIPANT_TOKEN_REQUIRED");
        var connection = connectionRepository.findByMeetingIdAndChatTokenHash(meetingId, MeetingChatTokenUtils.sha256(token))
            .orElseThrow(() -> new MeetingAccessDeniedException("INVALID_PARTICIPANT_TOKEN"));
        if (connection.getParticipant() == null || connection.getParticipant().getStatus() == ParticipantStatus.REMOVED
            || connection.getStatus() == ParticipantConnectionStatus.REMOVED)
            throw new MeetingAccessDeniedException("PARTICIPANT_REMOVED");
        if (credentials != null) credentials.validate(connection);
        var page = afterId == 0 ? chatMessageRepository.findByMeetingIdOrderByIdDesc(meetingId, org.springframework.data.domain.PageRequest.of(0, size))
            : chatMessageRepository.findByMeetingIdAndIdGreaterThanOrderByIdAsc(meetingId, afterId, org.springframework.data.domain.PageRequest.of(0, size));
        var rows = page.getContent().stream().sorted(java.util.Comparator.comparing(MeetingChatMessageEntity::getId)).map(this::toResponse).toList();
        Long cursor = rows.isEmpty() ? (afterId == 0 ? null : afterId) : rows.get(rows.size() - 1).id();
        return new vn.cusc.meetingservice.dto.response.MeetingChatPageResponse(rows, afterId > 0 && page.hasNext(), cursor);
    }

    private MeetingChatMessageResponse toResponse(
        MeetingChatMessageEntity message
    ) {
        return new MeetingChatMessageResponse(
            message.getId(),
            message.getClientMessageId(),
            message.getSenderDisplayName(),
            message.getMessageType(),
            message.getContent(),
            message.getSentAt(),
            message.getParticipantConnection() == null ? null : message.getParticipantConnection().getConnectionId()
        );
    }
}
