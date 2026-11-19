package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.MeetingSpeakingException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

class MeetingGuestIdentityServiceTest {
    private final MeetingParticipantRepository participants = mock(MeetingParticipantRepository.class);
    private final MeetingParticipantConnectionRepository connections = mock(MeetingParticipantConnectionRepository.class);
    private final MeetingGuestIdentityService service = new MeetingGuestIdentityService(participants, connections);
    @Test void knownPublicGuestIdWithoutProofAlwaysCreatesANewActor() {
        when(participants.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.resolve(meeting(), request("guest-victim", null));
        assertNotEquals("guest-victim", result.getUserId()); assertNull(result.getUserAccount());
        verifyNoInteractions(connections); verify(participants, never()).findByMeetingIdAndUserId(any(), any());
    }
    @Test void secretProofResumesOnlyItsOwnScopedGuest() {
        var meeting = meeting(); var connection = connection(meeting);
        when(connections.findByMeetingIdAndChatTokenHash(1L, MeetingChatTokenUtils.sha256("secret"))).thenReturn(Optional.of(connection));
        assertSame(connection.getParticipant(), service.resolve(meeting, request("guest-owner", "secret")));
        assertThrows(MeetingSpeakingException.class, () -> service.resolve(meeting, request("guest-victim", "secret")));
    }
    @Test void removedCredentialAndAccountBoundCredentialCannotResumeAGuest() {
        var meeting = meeting(); var connection = connection(meeting);
        when(connections.findByMeetingIdAndChatTokenHash(1L, MeetingChatTokenUtils.sha256("secret"))).thenReturn(Optional.of(connection));
        connection.setStatus(ParticipantConnectionStatus.REMOVED);
        assertThrows(MeetingSpeakingException.class, () -> service.resolve(meeting, request("guest-owner", "secret")));
        connection.setStatus(ParticipantConnectionStatus.LEFT); connection.getParticipant().setUserAccount(new UserAccountEntity());
        assertThrows(MeetingSpeakingException.class, () -> service.resolve(meeting, request("guest-owner", "secret")));
    }
    private MeetingEntity meeting() { var value = new MeetingEntity(); value.setId(1L); value.setAccessType(MeetingAccessType.PUBLIC); value.setCreationMode(MeetingCreationMode.SCHEDULED); return value; }
    private JoinMeetingRequest request(String id, String secret) { return new JoinMeetingRequest(ParticipantJoinType.GUEST, id, "Người tham gia", null, secret); }
    private MeetingParticipantConnectionEntity connection(MeetingEntity meeting) {
        var person = new MeetingParticipantEntity(); person.setMeeting(meeting); person.setUserId("guest-owner"); person.setStatus(ParticipantStatus.LEFT);
        var value = new MeetingParticipantConnectionEntity(); value.setMeeting(meeting); value.setParticipant(person); value.setStatus(ParticipantConnectionStatus.LEFT); return value;
    }
}
