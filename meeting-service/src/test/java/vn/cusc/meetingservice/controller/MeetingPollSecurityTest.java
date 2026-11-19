package vn.cusc.meetingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import vn.cusc.meetingservice.config.SecurityConfig;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.security.RestAccessDeniedHandler;
import vn.cusc.meetingservice.security.RestAuthenticationEntryPoint;
import vn.cusc.meetingservice.service.MeetingPollService;

@WebMvcTest(MeetingPollController.class)
@ContextConfiguration(classes = MeetingPollController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, MeetingPollSecurityTest.SessionDatabase.class, vn.cusc.meetingservice.exception.GlobalExceptionHandler.class})
class MeetingPollSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private SessionRepository<?> sessions;
    @MockBean private MeetingPollService polls;
    @MockBean private UserDetailsService users;
    @MockBean private vn.cusc.meetingservice.repository.UserAccountRepository accounts;

    private static final String BODY = """
        {"question":"Approve?","type":"YES_NO_ABSTAIN","allowChangeVote":false,
         "resultVisibility":"AFTER_CLOSE","minSelections":1,"maxSelections":1,
         "eligibleParticipantKeys":["member-key"]}
        """;

    @Test
    void anonymousCannotReadPolls() throws Exception {
        when(polls.list(1L, 0, 20, null)).thenThrow(new vn.cusc.meetingservice.exception.MeetingAccessDeniedException("POLL_ACCESS_FORBIDDEN"));
        mvc.perform(get("/api/meetings/1/polls")).andExpect(status().isForbidden());
        verify(polls).list(1L, 0, 20, null);
    }

    @Test
    void authenticatedPollWriteStillRequiresCsrf() throws Exception {
        mvc.perform(put("/api/meetings/1/polls/2").cookie(session())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    }

    @Test
    void userRoleReachesParticipantPolicyWithCsrf() throws Exception {
        Cookie session = session();
        var result = mvc.perform(get("/api/meetings/1/polls/context").cookie(session))
            .andExpect(status().isOk()).andReturn();
        CsrfToken token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        String submitted = token.getToken();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(put("/api/meetings/1/polls/2").cookie(session, cookie)
            .header(token.getHeaderName(), submitted).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk());
        verify(polls).update(eq(1L), eq(2L), any(), org.mockito.ArgumentMatchers.isNull());
        verify(polls).context(1L, null);
    }

    @Test
    void invalidPollPayloadIsRejectedBeforeService() throws Exception {
        Cookie session = session();
        var result = mvc.perform(get("/api/meetings/1/polls/context").cookie(session)).andReturn();
        CsrfToken token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        String submitted = token.getToken();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(put("/api/meetings/1/polls/2").cookie(session, cookie)
            .header(token.getHeaderName(), submitted).contentType(MediaType.APPLICATION_JSON)
            .content(BODY.replace("Approve?", " "))).andExpect(status().isBadRequest());
    }

    private Cookie session() {
        return authenticatedSession(sessions);
    }

    private <S extends Session> Cookie authenticatedSession(SessionRepository<S> repository) {
        var account = new vn.cusc.meetingservice.entity.UserAccountEntity(); account.setId(1L); account.setPublicId("user-id");
        account.setNormalizedEmail("user@example.test"); account.setFullName("User"); account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(SystemRole.USER);
        when(accounts.findById(1L)).thenReturn(java.util.Optional.of(account));
        var principal = new MeetingPrincipal(1L, "user-id", null, "user@example.test", "User", null,
            AccountStatus.ACTIVE, SystemRole.USER, true, AuthProvider.LOCAL, null);
        var context = new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
        S session = repository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repository.save(session);
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration
    static class SessionDatabase {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("org/springframework/session/jdbc/schema-h2.sql").build();
        }

        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
