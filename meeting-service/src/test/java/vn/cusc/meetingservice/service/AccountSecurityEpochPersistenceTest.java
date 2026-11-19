package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.config.*;
import vn.cusc.meetingservice.dto.auth.request.UpdateUserRequest;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.service.impl.UserAccountServiceImpl;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ActiveProfiles("test")
@Import({UserAccountServiceImpl.class, AccountSecurityEpochPersistenceTest.Beans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountSecurityEpochPersistenceTest {
    @Autowired UserAccountService service;
    @Autowired UserAccountRepository accounts;
    @Autowired GoogleAuthProperties google;
    @Test void disableAndRoleChangesAdvanceEpochAndActivateNeverResetsIt() {
        var account = account(); String id = account.getPublicId();
        assertEquals(1, service.disable(id).getSecurityVersion()); assertEquals(2, service.activate(id).getSecurityVersion());
        assertEquals(3, service.update(id, new UpdateUserRequest(null, SystemRole.ADMIN, null)).getSecurityVersion());
        assertEquals(3, service.update(id, new UpdateUserRequest(null, SystemRole.ADMIN, null)).getSecurityVersion());
    }
    @Test void staleProfileOrLoginWriteCannotUndoConcurrentDisable() {
        var stale = account(); service.disable(stale.getPublicId());
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class, () -> service.touchLastLogin(stale));
        var fresh = accounts.findById(stale.getId()).orElseThrow();
        assertEquals(AccountStatus.DISABLED, fresh.getAccountStatus()); assertEquals(1, fresh.getSecurityVersion());
    }
    @Test void onlyNewGoogleAccountUsesConfiguredAutoProvision() {
        google.setAutoProvision(true);
        var account = service.findOrCreateGoogleAccount(new GoogleIdTokenVerifierService.VerifiedGoogleToken(UUID.randomUUID().toString(),
            UUID.randomUUID() + "@example.test", "Google Actor", null, true, "issuer", "audience"));
        assertEquals(AccountStatus.ACTIVE, account.getAccountStatus());
        google.setAutoProvision(false);
        var pending = service.findOrCreateGoogleAccount(new GoogleIdTokenVerifierService.VerifiedGoogleToken(UUID.randomUUID().toString(),
            UUID.randomUUID() + "@example.test", "Google Actor", null, true, "issuer", "audience"));
        assertEquals(AccountStatus.PENDING_APPROVAL, pending.getAccountStatus());
    }
    @Test void activatingALegacyDisabledEpochZeroAccountInvalidatesLegacyCredentials() {
        var legacy = account(); legacy.setAccountStatus(AccountStatus.DISABLED); accounts.saveAndFlush(legacy);
        var fresh = service.activate(legacy.getPublicId()); assertEquals(1, fresh.getSecurityVersion());
        var connection = new vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity();
        assertFalse(vn.cusc.meetingservice.security.AccountCredentialVersions.matches(connection, fresh));
    }
    private UserAccountEntity account() {
        var value = new UserAccountEntity(); value.setPublicId(UUID.randomUUID().toString()); value.setUsername("actor-" + UUID.randomUUID());
        value.setNormalizedEmail(UUID.randomUUID() + "@example.test"); value.setFullName("Actor"); value.setAccountStatus(AccountStatus.ACTIVE);
        value.setSystemRole(SystemRole.USER); return accounts.saveAndFlush(value);
    }
    @TestConfiguration static class Beans {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean AuthProperties authProperties() { return new AuthProperties(); }
        @Bean GoogleAuthProperties googleAuthProperties() { return new GoogleAuthProperties(); }
    }
}
