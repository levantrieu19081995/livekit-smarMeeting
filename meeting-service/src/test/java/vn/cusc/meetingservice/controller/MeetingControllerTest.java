package vn.cusc.meetingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.response.MeetingDetailResponse;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.exception.GlobalExceptionHandler;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.service.MeetingService;

class MeetingControllerTest {

    private final MeetingService meetingService = Mockito.mock(MeetingService.class);
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new MeetingController(meetingService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void createMeetingReturnsCreated() throws Exception {
        CreateMeetingRequest request = new CreateMeetingRequest(
            "Hop trien khai",
            "Mo ta",
            LocalDateTime.of(2026, 8, 12, 9, 0),
            LocalDateTime.of(2026, 8, 12, 11, 0),
            MeetingAccessType.PRIVATE,
            List.of(new AddMeetingParticipantRequest("uuid-1", null, "Nguyen Van A", MeetingRole.CHAIRMAN))
        );

        when(meetingService.createMeeting(any())).thenReturn(new MeetingResponse(
            1L,
            "MTG-20260812-0001",
            "Hop trien khai",
            "Mo ta",
            "meeting-a81f2d43",
            "RM_1",
            "http://localhost:3000/join/1",
            request.startTime(),
            request.endTime(),
            MeetingStatus.UPCOMING,
            MeetingAccessType.PRIVATE
        ));

        mockMvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.meetingCode").value("MTG-20260812-0001"));
    }

    @Test
    void getMeetingReturnsDetail() throws Exception {
        when(meetingService.getMeeting(1L)).thenReturn(new MeetingDetailResponse(
            1L,
            "MTG-20260812-0001",
            "Hop",
            "Mo ta",
            "meeting-room",
            "RM_1",
            "http://localhost:3000/join/1",
            LocalDateTime.of(2026, 8, 12, 9, 0),
            LocalDateTime.of(2026, 8, 12, 11, 0),
            MeetingStatus.UPCOMING,
            MeetingAccessType.PRIVATE,
            List.of(new MeetingParticipantResponse(1L, "pk-1", "account-uuid", "uuid-1", "a@cusc.vn", "Nguyen Van A", "a@cusc.vn", MeetingRole.CHAIRMAN, ParticipantStatus.INVITED, null, null))
        ));

        mockMvc.perform(get("/api/meetings/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participants[0].participantKey").value("pk-1"));
    }
}
