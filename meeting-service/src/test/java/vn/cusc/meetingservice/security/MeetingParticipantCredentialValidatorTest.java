package vn.cusc.meetingservice.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.cusc.meetingservice.entity.*;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.UserAccountRepository;

class MeetingParticipantCredentialValidatorTest {
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final CurrentUserService current = mock(CurrentUserService.class);
    private final MeetingParticipantCredentialValidator validator = new MeetingParticipantCredentialValidator(accounts, current);
    @Test void oldBearerCannotReviveAfterAccountReEnable() {
        var account = account(); account.setSecurityVersion(1); var connection = connection(account); connection.setAccountSecurityVersion(0L);
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
        assertThrows(MeetingAccessDeniedException.class, () -> validator.validate(connection));
        connection.setAccountSecurityVersion(1L); assertDoesNotThrow(() -> validator.validate(connection));
    }
    @Test void legacyNullEpochIsValidOnlyWhileAccountEpochIsZero() {
        var account = account(); var connection = connection(account); when(accounts.findById(1L)).thenReturn(Optional.of(account));
        assertDoesNotThrow(() -> validator.validate(connection)); account.setSecurityVersion(1);
        assertThrows(MeetingAccessDeniedException.class, () -> validator.validate(connection));
    }
    @Test void privateBearerStillRequiresLoggedInAccountAndMatchingIdentity() {
        var account = account(); var connection = connection(account); connection.getMeeting().setAccessType(MeetingAccessType.PRIVATE);
        assertThrows(MeetingAccessDeniedException.class, () -> validator.validate(connection));
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
        when(current.getCurrentPrincipal()).thenReturn(Optional.of(principal(2L, "other")));
        assertThrows(MeetingAccessDeniedException.class, () -> validator.validate(connection));
        when(current.getCurrentPrincipal()).thenReturn(Optional.of(principal(1L, "person")));
        assertDoesNotThrow(() -> validator.validate(connection));
    }
    private MeetingPrincipal principal(Long id, String publicId) { return new MeetingPrincipal(id, publicId, publicId, "a@example.test", "Actor", null, AccountStatus.ACTIVE, SystemRole.USER, true, AuthProvider.LOCAL, null); }
    private UserAccountEntity account() { var value = new UserAccountEntity(); value.setId(1L); value.setPublicId("person"); value.setAccountStatus(AccountStatus.ACTIVE); return value; }
    private MeetingParticipantConnectionEntity connection(UserAccountEntity account) {
        var meeting = new MeetingEntity(); meeting.setAccessType(MeetingAccessType.PUBLIC);
        var participant = new MeetingParticipantEntity(); participant.setUserAccount(account); participant.setMeeting(meeting);
        var value = new MeetingParticipantConnectionEntity(); value.setParticipant(participant); value.setMeeting(meeting); return value;
    }
}
