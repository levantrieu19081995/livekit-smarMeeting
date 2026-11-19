package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.cusc.meetingservice.dto.request.SaveMeetingChatMessageRequest;
import vn.cusc.meetingservice.dto.response.MeetingChatMessageResponse;
import vn.cusc.meetingservice.model.enums.MeetingChatMessageType;
import vn.cusc.meetingservice.service.MeetingChatMessageService;

class MeetingChatMessageControllerTest {

    private final MeetingChatMessageService chatMessageService =
        Mockito.mock(MeetingChatMessageService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
            new MeetingChatMessageController(chatMessageService)
        ).build();
    }

    @Test
    void postMessageForwardsParticipantTokenAndBody() throws Exception {
        when(chatMessageService.saveMessage(
            eq(11L),
            eq("chat-token-1"),
            Mockito.any(SaveMeetingChatMessageRequest.class)
        )).thenReturn(new MeetingChatMessageResponse(
            123L,
            "550e8400-e29b-41d4-a716-446655440000",
            "Nguyen Van A",
            MeetingChatMessageType.TEXT,
            "Toi dong y phuong an nay",
            LocalDateTime.of(2026, 8, 25, 15, 30, 21)
        ));

        mockMvc.perform(post("/api/meetings/11/chat/messages")
                .header("X-Meeting-Participant-Token", "chat-token-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
                      "content": "Toi dong y phuong an nay"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(123))
            .andExpect(jsonPath("$.clientMessageId").value("550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(jsonPath("$.senderDisplayName").value("Nguyen Van A"))
            .andExpect(jsonPath("$.messageType").value("TEXT"))
            .andExpect(jsonPath("$.content").value("Toi dong y phuong an nay"))
            .andExpect(jsonPath("$.sentAt").exists());

        ArgumentCaptor<SaveMeetingChatMessageRequest> requestCaptor =
            ArgumentCaptor.forClass(SaveMeetingChatMessageRequest.class);
        Mockito.verify(chatMessageService).saveMessage(
            eq(11L),
            eq("chat-token-1"),
            requestCaptor.capture()
        );
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            requestCaptor.getValue().clientMessageId()
        );
        assertEquals(
            "Toi dong y phuong an nay",
            requestCaptor.getValue().content()
        );
    }
}
