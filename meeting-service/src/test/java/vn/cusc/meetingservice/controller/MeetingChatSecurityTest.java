package vn.cusc.meetingservice.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.cusc.meetingservice.config.SecurityConfig;
import vn.cusc.meetingservice.dto.response.MeetingChatPageResponse;
import vn.cusc.meetingservice.exception.*;
import vn.cusc.meetingservice.security.*;
import vn.cusc.meetingservice.service.MeetingChatMessageService;

@WebMvcTest(MeetingChatMessageController.class)
@ContextConfiguration(classes = MeetingChatMessageController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
    GlobalExceptionHandler.class, MeetingPollSecurityTest.SessionDatabase.class})
class MeetingChatSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean MeetingChatMessageService chat;
    @MockBean UserDetailsService users;
    @Test void publicGuestHistoryReachesScopedCapabilityPolicyWithoutRequiringLogin() throws Exception {
        when(chat.listMessages(1L, "guest-secret", 0, 100)).thenReturn(new MeetingChatPageResponse(List.of(), false, null));
        mvc.perform(get("/api/meetings/1/chat/messages").header("X-Meeting-Participant-Token", "guest-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        verify(chat).listMessages(1L, "guest-secret", 0, 100);
    }
    @Test void missingOrPrivateInvalidCredentialIsRejectedByTheDomainAndOtherRoutesStayProtected() throws Exception {
        when(chat.listMessages(1L, null, 0, 100)).thenThrow(new MeetingAccessDeniedException("CHAT_PARTICIPANT_TOKEN_REQUIRED"));
        mvc.perform(get("/api/meetings/1/chat/messages")).andExpect(status().isForbidden());
        when(chat.listMessages(2L, "account-secret", 0, 100)).thenThrow(new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/meetings/2/chat/messages").header("X-Meeting-Participant-Token", "account-secret")).andExpect(status().isForbidden());
        mvc.perform(get("/api/meetings/1/documents")).andExpect(status().isUnauthorized());
    }
}
