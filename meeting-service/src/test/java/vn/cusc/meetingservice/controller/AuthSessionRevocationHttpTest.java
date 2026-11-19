package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import vn.cusc.meetingservice.dto.auth.request.UpdateUserRequest;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.service.*;

@SpringBootTest(properties = "spring.session.jdbc.initialize-schema=always")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSessionRevocationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired UserAccountService users;
    @Autowired PasswordEncoder passwords;
    @Autowired SessionRepository<?> sessions;
    @Autowired JdbcTemplate jdbc;
    @MockBean LiveKitService liveKit;
    @BeforeEach void schema() {
        jdbc.execute("create table if not exists SPRING_SESSION (PRIMARY_ID varchar(36) primary key, SESSION_ID varchar(36) not null, CREATION_TIME bigint not null, LAST_ACCESS_TIME bigint not null, MAX_INACTIVE_INTERVAL int not null, EXPIRY_TIME bigint not null, PRINCIPAL_NAME varchar(100))");
        jdbc.execute("create table if not exists SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID varchar(36) not null, ATTRIBUTE_NAME varchar(200) not null, ATTRIBUTE_BYTES bytea not null, primary key(SESSION_PRIMARY_ID, ATTRIBUTE_NAME), foreign key(SESSION_PRIMARY_ID) references SPRING_SESSION(PRIMARY_ID) on delete cascade)");
    }
    @Test void realSessionCookieIsRotatedAndPreviousIdNoLongerCarriesAuthentication() throws Exception {
        var account = account(SystemRole.USER); Cookie previous = anonymousSession(sessions); String oldId = decode(previous);
        Cookie logged = login(account, previous); assertNotEquals(oldId, decode(logged));
        assertNull(sessions.findById(oldId)); assertNotNull(sessions.findById(decode(logged)));
        mvc.perform(get("/api/auth/me").cookie(logged)).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/meetings").cookie(previous)).andExpect(status().isUnauthorized());
    }
    @Test void disableThenReEnableDoesNotRestorePreviouslyIssuedCookie() throws Exception {
        var account = account(SystemRole.USER); Cookie logged = login(account, null);
        users.disable(account.getPublicId()); users.activate(account.getPublicId());
        mvc.perform(get("/api/meetings").cookie(logged)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_SESSION_REVOKED"));
        Cookie fresh = login(accounts.findById(account.getId()).orElseThrow(), null);
        mvc.perform(get("/api/auth/me").cookie(fresh)).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
    }
    @Test void globalRoleDemotionRevokesOldAdminCookieAndNewUserCannotAccessAdminApi() throws Exception {
        var account = account(SystemRole.ADMIN); Cookie logged = login(account, null);
        mvc.perform(get("/api/users").cookie(logged)).andExpect(status().isOk());
        users.update(account.getPublicId(), new UpdateUserRequest(null, SystemRole.USER, null));
        mvc.perform(get("/api/users").cookie(logged)).andExpect(status().isUnauthorized());
        Cookie fresh = login(accounts.findById(account.getId()).orElseThrow(), null);
        mvc.perform(get("/api/users").cookie(fresh)).andExpect(status().isForbidden());
    }
    @Test void logoutClearsActualSessionCookieAndRefreshedCsrfStillWorks() throws Exception {
        Cookie logged = login(account(SystemRole.USER), null); var csrf = csrf(logged);
        var response = mvc.perform(post("/api/auth/logout").cookie(logged, csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isNoContent()).andReturn().getResponse();
        assertTrue(java.util.Arrays.stream(response.getCookies()).anyMatch(value -> value.getName().equals("SESSION") && value.getMaxAge() == 0));
        assertNull(sessions.findById(decode(logged)));
        mvc.perform(get("/api/meetings").cookie(logged)).andExpect(status().isUnauthorized());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = AccountStatus.class, names = {"DISABLED", "PENDING_APPROVAL"})
    void inactiveLocalLoginReturnsADefinedClientErrorWithoutIssuingASession(AccountStatus state) throws Exception {
        var account = account(SystemRole.USER); account.setAccountStatus(state); accounts.saveAndFlush(account);
        var csrf = csrf(null);
        mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("usernameOrEmail", account.getUsername(), "password", "safe-password"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BUSINESS_VALIDATION_ERROR"))
            .andExpect(cookie().doesNotExist("SESSION"));
    }
    private Cookie login(UserAccountEntity account, Cookie existing) throws Exception {
        var csrf = csrf(existing);
        var request = post("/api/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("usernameOrEmail", account.getUsername(), "password", "safe-password")));
        if (existing != null) request.cookie(existing);
        Cookie result = mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
            .andReturn().getResponse().getCookie("SESSION"); assertNotNull(result); return result;
    }
    private record Csrf(Cookie cookie, String token) { }
    private Csrf csrf(Cookie session) throws Exception {
        var request = get("/api/auth/csrf"); if (session != null) request.cookie(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    private UserAccountEntity account(SystemRole role) {
        var value = new UserAccountEntity(); value.setPublicId(UUID.randomUUID().toString()); value.setUsername("http-" + UUID.randomUUID());
        value.setNormalizedEmail(UUID.randomUUID() + "@example.test"); value.setFullName("HTTP Actor"); value.setPasswordHash(passwords.encode("safe-password"));
        value.setAccountStatus(AccountStatus.ACTIVE); value.setSystemRole(role); return accounts.saveAndFlush(value);
    }
    private <S extends Session> Cookie anonymousSession(SessionRepository<S> repository) {
        S session = repository.createSession(); session.setAttribute("pre-login", true); repository.save(session);
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    private String decode(Cookie cookie) { return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8); }
}
