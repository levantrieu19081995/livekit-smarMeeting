package vn.cusc.meetingservice.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.dto.stt.callback.SttProgressCallbackRequest;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.LiveKitWebhookService;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.repository.UserAccountRepository;

@SpringBootTest(properties = {
    "spring.session.jdbc.initialize-schema=always",
    "ai-service.internal-api-key=test-internal-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private UserAccountRepository accounts;
    @MockBean private LiveKitService liveKit;

    @MockBean
    private MeetingMinutesSttJobStateService sttJobStateService;

    @MockBean
    private LiveKitWebhookService liveKitWebhookService;

    @BeforeEach
    void ensureSpringSessionSchema() {
        jdbcTemplate.execute("""
            create table if not exists SPRING_SESSION (
                PRIMARY_ID varchar(36) not null,
                SESSION_ID varchar(36) not null,
                CREATION_TIME bigint not null,
                LAST_ACCESS_TIME bigint not null,
                MAX_INACTIVE_INTERVAL int not null,
                EXPIRY_TIME bigint not null,
                PRINCIPAL_NAME varchar(100),
                constraint SPRING_SESSION_PK primary key (PRIMARY_ID)
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists SPRING_SESSION_ATTRIBUTES (
                SESSION_PRIMARY_ID varchar(36) not null,
                ATTRIBUTE_NAME varchar(200) not null,
                ATTRIBUTE_BYTES bytea not null,
                constraint SPRING_SESSION_ATTRIBUTES_PK primary key (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                constraint SPRING_SESSION_ATTRIBUTES_FK foreign key (SESSION_PRIMARY_ID)
                references SPRING_SESSION(PRIMARY_ID) on delete cascade
            )
            """);
    }

    @Test
    void csrfEndpointReturnsTokenAndCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(jsonPath("$.parameterName").value("_csrf"))
            .andExpect(cookie().exists("XSRF-TOKEN"))
            .andExpect(cookie().path("XSRF-TOKEN", "/"));
    }

    @Test
    void registerWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerPayload()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void registerWithCsrfTokenAndCookiePassesCsrfFilter() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/auth/register")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerPayload()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void usersApiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void minutesDocumentsAndMeetingManagementGetRequireAuthenticationButJoinInfoRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/minutes"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/meetings/1/minutes/current"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/meetings/1/documents"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/meetings"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/meetings/999999"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/meetings/999999/join-info"))
            .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedUserMeetingDetailReachesApplicationLayer() throws Exception {
        MeetingPrincipal principal = principal(91L, SystemRole.USER);
        var authentication = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        var securityContext = new SecurityContextImpl(authentication);

        mockMvc.perform(get("/api/meetings/999999")
                .requestAttr(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, securityContext))
            .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedUserEndMeetingReachesApplicationLayer() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        MeetingPrincipal principal = principal(92L, SystemRole.USER);
        var authentication = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        var securityContext = new SecurityContextImpl(authentication);

        mockMvc.perform(post("/api/meetings/999999/end")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .requestAttr(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, securityContext))
            .andExpect(status().isNotFound());
    }

    @Test
    void anonymousEndMeetingIsRejectedBySpringSecurity() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/meetings/999999/end")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void normalUserCanCreateAnOpenMeetingWithCsrf() throws Exception {
        org.mockito.Mockito.when(liveKit.createRoom(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> new vn.cusc.meetingservice.dto.response.LiveKitRoomResponse(call.getArgument(0), "RM_security", 0, 0));
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        MeetingPrincipal principal = principal(93L, SystemRole.USER);
        var authentication = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        var securityContext = new SecurityContextImpl(authentication);

        mockMvc.perform(post("/api/meetings")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .requestAttr(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, securityContext)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "Security Meeting",
                      "description": "Security regression",
                      "startTime": "2026-09-08T09:00:00",
                      "endTime": "2026-09-08T10:00:00",
                      "accessType": "PUBLIC",
                      "participants": []
                    }
                    """))
            .andExpect(status().isCreated());
    }

    @Test
    void participantEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/meetings/1/participants"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void publicJoinEndpointRemainsReachableWithCsrf() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/meetings/999999/join")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "participantType": "GUEST",
                      "guestId": "guest-123",
                      "displayName": "Guest User"
                    }
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void liveKitWebhookWithoutCsrfReachesServiceLayer() throws Exception {
        mockMvc.perform(post("/api/livekit/webhook")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(liveKitWebhookService).handleWebhook("Bearer token", "{}");
    }

    @Test
    void sttProgressCallbackWithoutCsrfPassesWithValidInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/progress")
                .header("X-Internal-Api-Key", "test-internal-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttProgressPayload()))
            .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(sttJobStateService).applySttProgress(
            org.mockito.Mockito.eq("python-123"),
            org.mockito.Mockito.any(SttProgressCallbackRequest.class)
        );
    }

    @Test
    void sttProgressCallbackWithoutCsrfRejectsInvalidInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/progress")
                .header("X-Internal-Api-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttProgressPayload()))
            .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(sttJobStateService);
    }

    @Test
    void sttProgressCallbackWithoutCsrfRejectsMissingInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/progress")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttProgressPayload()))
            .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(sttJobStateService);
    }

    @Test
    void sttCompletionCallbackWithoutCsrfPassesWithValidInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/completion")
                .header("X-Internal-Api-Key", "test-internal-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttCompletionPayload()))
            .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(sttJobStateService).applySttCompletion(
            org.mockito.Mockito.eq("python-123"),
            org.mockito.Mockito.any(SttCompletionCallbackRequest.class)
        );
    }

    @Test
    void sttCompletionCallbackWithoutCsrfRejectsInvalidInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/completion")
                .header("X-Internal-Api-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttCompletionPayload()))
            .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(sttJobStateService);
    }

    @Test
    void sttCompletionCallbackWithoutCsrfRejectsMissingInternalApiKey() throws Exception {
        mockMvc.perform(post("/internal/api/minutes/stt/jobs/python-123/completion")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sttCompletionPayload()))
            .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(sttJobStateService);
    }

    private String registerPayload() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return """
            {
              "username": "csrf_user_%s",
              "fullName": "CSRF User",
              "email": "csrf_%s@cusc.vn",
              "password": "password-123"
            }
            """.formatted(suffix, suffix);
    }

    private String sttProgressPayload() {
        return """
            {
              "meetingId": 12,
              "processedChunks": 1,
              "totalChunks": 3,
              "progressPercent": 33.33
            }
            """;
    }

    private String sttCompletionPayload() {
        return """
            {
              "meetingId": 12,
              "status": "COMPLETED",
              "totalManifests": 2,
              "totalSegments": 3,
              "totalChunks": 3,
              "processedChunks": 3,
              "succeededChunks": 3,
              "failedChunks": 0,
              "errorMessage": null
            }
            """;
    }

    private MeetingPrincipal principal(Long accountId, SystemRole role) {
        var account = new UserAccountEntity(); account.setPublicId(UUID.randomUUID().toString());
        account.setUsername("security-user-" + accountId + "-" + UUID.randomUUID().toString().substring(0, 8));
        account.setNormalizedEmail(UUID.randomUUID() + "@cusc.vn"); account.setFullName("User " + accountId);
        account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(role); account.setEmailVerified(true);
        account = accounts.saveAndFlush(account);
        return new MeetingPrincipal(
            account.getId(),
            account.getPublicId(),
            account.getUsername(),
            account.getNormalizedEmail(),
            "User " + accountId,
            null,
            AccountStatus.ACTIVE,
            role,
            true,
            AuthProvider.LOCAL,
            null
        );
    }

}
