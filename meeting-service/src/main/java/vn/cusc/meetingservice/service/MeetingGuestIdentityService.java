package vn.cusc.meetingservice.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingSpeakingException;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

/** Used under the caller's meeting transaction/lock. Public identifiers never prove guest ownership. */
@Service
public class MeetingGuestIdentityService {
    private final MeetingParticipantRepository participants;
    private final MeetingParticipantConnectionRepository connections;
    public MeetingGuestIdentityService(MeetingParticipantRepository participants, MeetingParticipantConnectionRepository connections) {
        this.participants = participants; this.connections = connections;
    }

    public MeetingParticipantEntity resolve(MeetingEntity meeting, JoinMeetingRequest request) {
        MeetingParticipantEntity resumed = validateResume(meeting, request);
        if (resumed != null) { resumed.setFullName(request.displayName().trim()); return resumed; }
        var participant = new MeetingParticipantEntity();
        String guestId = "guest-" + UUID.randomUUID();
        participant.setMeeting(meeting); participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setUserId(guestId); participant.setFullName(request.displayName().trim());
        participant.setEmail(guestId + "@guest.local"); participant.setRole(MeetingRole.GUEST);
        participant.setStatus(ParticipantStatus.INVITED); participant.setJoinType(ParticipantJoinType.GUEST);
        return participants.save(participant);
    }

    /** Preflight validates without changing the actor, writing rows or consuming a credential. */
    public MeetingParticipantEntity validateResume(MeetingEntity meeting, JoinMeetingRequest request) {
        if (request == null || request.displayName() == null || request.displayName().trim().isEmpty()
            || request.displayName().length() > 255 || request.displayName().codePoints().anyMatch(Character::isISOControl))
            throw new BusinessValidationException("Tên tham gia cần từ 1 đến 255 ký tự, không có ký tự điều khiển.");
        String proof = request.guestResumeToken();
        if (proof == null) return null;
        if (proof.isBlank() || proof.length() > 255 || request.guestId() == null) throw invalid(meeting);
        var previous = connections.findByMeetingIdAndChatTokenHash(meeting.getId(), MeetingChatTokenUtils.sha256(proof))
            .orElseThrow(() -> invalid(meeting));
        var participant = previous.getParticipant();
        if (participant == null || participant.getUserAccount() != null || participant.getStatus() == ParticipantStatus.REMOVED
            || !Objects.equals(participant.getMeeting().getId(), meeting.getId())
            || !Objects.equals(participant.getUserId(), request.guestId())
            || previous.getStatus() == ParticipantConnectionStatus.REMOVED
            || meeting.getCreationMode() == MeetingCreationMode.POINT && previous.getPoint() == null) throw invalid(meeting);
        return participant;
    }

    private MeetingSpeakingException invalid(MeetingEntity meeting) {
        return new MeetingSpeakingException(HttpStatus.FORBIDDEN,
            meeting.getCreationMode() == MeetingCreationMode.POINT ? "SPEAKING_INVALID_RESUME_TOKEN" : "MEETING_INVALID_RESUME_TOKEN",
            "Không thể khôi phục phiên khách. Vui lòng tham gia bằng phiên mới.");
    }
}
