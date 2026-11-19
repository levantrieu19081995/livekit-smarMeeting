package vn.cusc.meetingservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import vn.cusc.meetingservice.config.SecurityConfig;
import vn.cusc.meetingservice.dto.qa.MeetingQaContextResponse;
import vn.cusc.meetingservice.exception.GlobalExceptionHandler;
import vn.cusc.meetingservice.exception.MeetingQaException;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.security.RestAccessDeniedHandler;
import vn.cusc.meetingservice.security.RestAuthenticationEntryPoint;
import vn.cusc.meetingservice.service.MeetingQaService;

@WebMvcTest(MeetingQaController.class)
@ContextConfiguration(classes = MeetingQaController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
    MeetingQaExceptionHandler.class, GlobalExceptionHandler.class, MeetingQaSecurityTest.SessionDatabase.class})
class MeetingQaSecurityTest {
    private static final String BODY = "{\"clientQuestionId\":\"13ad1fc4-ae76-430e-9a88-1fc16be4c428\",\"content\":\"Question\",\"anonymous\":true}";
    @Autowired private MockMvc mvc;
    @MockBean private MeetingQaService qa;
    @MockBean private UserDetailsService users;

    @Test
    void guestCredentialReachesPolicyWithoutOpeningOtherRoutes() throws Exception {
        when(qa.context(1L, "guest-token")).thenReturn(new MeetingQaContextResponse(true, true, false,
            0, MeetingStatus.IN_PROGRESS, false, true, true));
        mvc.perform(get("/api/meetings/1/qa/context").header("X-Meeting-Participant-Token", "guest-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canAsk").value(true));
        verify(qa).context(1L, "guest-token");
        mvc.perform(get("/api/meetings/1/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/meetings/1/minutes/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/meetings/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingCredentialsAreDeniedByQaPolicyAndMappedAsForbidden() throws Exception {
        when(qa.context(1L, null)).thenThrow(new MeetingQaException(HttpStatus.FORBIDDEN,
            "QA_ACCESS_FORBIDDEN", "Forbidden"));
        mvc.perform(get("/api/meetings/1/qa/context")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("QA_ACCESS_FORBIDDEN"));
    }

    @Test
    void guestWriteStillRequiresCsrf() throws Exception {
        mvc.perform(post("/api/meetings/1/qa/questions").header("X-Meeting-Participant-Token", "guest-token")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(qa);
    }

    @Test
    void guestCanSubmitAndSetUpvoteWithCsrfThroughExactEndpoints() throws Exception {
        mvc.perform(csrf(post("/api/meetings/1/qa/questions")).header("X-Meeting-Participant-Token", "guest-token")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        verify(qa).ask(eq(1L), eq("guest-token"), argThat(request -> request.anonymous() && request.content().equals("Question")));
        mvc.perform(csrf(put("/api/meetings/1/qa/questions/9/upvote")).header("X-Meeting-Participant-Token", "guest-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"upvoted\":false}")).andExpect(status().isOk());
        verify(qa).upvote(eq(1L), eq(9L), eq("guest-token"), argThat(request -> !request.upvoted()));
    }

    @Test
    void invalidPayloadAndEnumsReturn400BeforeService() throws Exception {
        mvc.perform(csrf(post("/api/meetings/1/qa/questions")).contentType(MediaType.APPLICATION_JSON)
            .content(BODY.replace("Question", " "))).andExpect(status().isBadRequest());
        verify(qa, never()).ask(any(), any(), any());
        mvc.perform(csrf(put("/api/meetings/1/qa/questions/9/upvote")).contentType(MediaType.APPLICATION_JSON)
            .content("{}" )).andExpect(status().isBadRequest());
        verify(qa, never()).upvote(any(), any(), any(), any());
        mvc.perform(get("/api/meetings/1/qa/questions?sort=UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(csrf(post("/api/meetings/1/qa/questions")).contentType(MediaType.APPLICATION_JSON)
            .content("{" )).andExpect(status().isBadRequest());
    }

    @Test
    void conflictAndMissingQuestionKeepDomainStatus() throws Exception {
        when(qa.ask(eq(1L), any(), any())).thenThrow(new MeetingQaException(HttpStatus.CONFLICT, "QA_CONFLICT", "Disabled"));
        mvc.perform(csrf(post("/api/meetings/1/qa/questions")).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QA_CONFLICT"));
        when(qa.upvote(eq(1L), eq(9L), any(), any())).thenThrow(new MeetingQaException(HttpStatus.NOT_FOUND, "QA_QUESTION_NOT_FOUND", "Missing"));
        mvc.perform(csrf(put("/api/meetings/1/qa/questions/9/upvote")).contentType(MediaType.APPLICATION_JSON).content("{\"upvoted\":true}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QA_QUESTION_NOT_FOUND"));
    }

    @Test
    void moderatorRoutesKeepCsrfAndPassVersionedPayloadToPolicy() throws Exception {
        String settings = "{\"enabled\":false,\"anonymousAllowed\":true,\"moderationEnabled\":true,\"expectedVersion\":2}";
        String action = "{\"action\":\"APPROVE\",\"expectedVersion\":4}";
        mvc.perform(put("/api/meetings/1/qa/settings").contentType(MediaType.APPLICATION_JSON).content(settings))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/meetings/1/qa/questions/9/actions").contentType(MediaType.APPLICATION_JSON).content(action))
            .andExpect(status().isForbidden());
        mvc.perform(csrf(put("/api/meetings/1/qa/settings")).contentType(MediaType.APPLICATION_JSON).content(settings))
            .andExpect(status().isOk());
        verify(qa).updateSettings(eq(1L), isNull(), argThat(request -> request.expectedVersion() == 2 && !request.enabled()));
        mvc.perform(csrf(post("/api/meetings/1/qa/questions/9/actions")).contentType(MediaType.APPLICATION_JSON).content(action))
            .andExpect(status().isOk());
        verify(qa).moderate(eq(1L), eq(9L), isNull(), argThat(request -> request.expectedVersion() == 4));
    }

    @Test
    void moderatorMissingFieldsAndUnknownActionsAreRejectedBeforeService() throws Exception {
        mvc.perform(csrf(put("/api/meetings/1/qa/settings")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true,\"anonymousAllowed\":true,\"moderationEnabled\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(csrf(post("/api/meetings/1/qa/questions/9/actions")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"ANSWER\",\"expectedVersion\":-1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(csrf(post("/api/meetings/1/qa/questions/9/actions")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"RESTORE\",\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
        verify(qa, never()).updateSettings(any(), any(), any());
        verify(qa, never()).moderate(any(), any(), any(), any());
    }

    @Test
    void reportUsesUtf8BomAttachmentAndNoStoreWithNoCsrfMutation() throws Exception {
        String csv = "\uFEFF\"Nội dung câu hỏi\",\"Người hỏi\"\r\n\"Tiếng Việt\",\"Ẩn danh\"\r\n";
        when(qa.report(1L, null)).thenReturn(csv);
        mvc.perform(get("/api/meetings/1/qa/report")).andExpect(status().isOk())
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"hoi-dap-cuoc-hop-1.csv\""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes(csv.getBytes(StandardCharsets.UTF_8)));
        verify(qa).report(1L, null);
    }

    @Test
    void reportDenialUsesQaPolicyAndDoesNotOpenOtherExports() throws Exception {
        when(qa.report(1L, "guest-token")).thenThrow(new MeetingQaException(HttpStatus.FORBIDDEN,
            "QA_MODERATION_FORBIDDEN", "Forbidden"));
        mvc.perform(get("/api/meetings/1/qa/report").header("X-Meeting-Participant-Token", "guest-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QA_MODERATION_FORBIDDEN"));
        mvc.perform(get("/api/meetings/1/qa/report").accept("text/csv").header("X-Meeting-Participant-Token", "guest-token"))
            .andExpect(status().isForbidden()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("QA_MODERATION_FORBIDDEN"));
        mvc.perform(get("/api/meetings/1/minutes/current")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(get("/api/meetings/1/qa/context")).andReturn();
        CsrfToken token = (CsrfToken) response.getRequest().getAttribute(CsrfToken.class.getName());
        String value = token.getToken();
        Cookie cookie = response.getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(cookie).header(token.getHeaderName(), value);
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
