package vn.cusc.meetingservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.cusc.meetingservice.dto.response.MinutesRenderResponse;
import vn.cusc.meetingservice.dto.response.MinutesTemplateResponse;
import vn.cusc.meetingservice.exception.GlobalExceptionHandler;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.service.MeetingMinutesPdfService;
import vn.cusc.meetingservice.service.MeetingMinutesTemplateService;

class MeetingMinutesTemplateControllerTest {

    private final MeetingMinutesTemplateService templateService =
        Mockito.mock(MeetingMinutesTemplateService.class);
    private final MeetingMinutesPdfService pdfService =
        Mockito.mock(MeetingMinutesPdfService.class);
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new MeetingMinutesTemplateController(templateService, pdfService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listTemplatesReturnsCuscStandard() throws Exception {
        when(templateService.listTemplates()).thenReturn(List.of(template()));

        mockMvc.perform(get("/api/minutes/templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("CUSC_STANDARD"));
    }

    @Test
    void renderValidRequestReturnsContract() throws Exception {
        when(templateService.render(eq(12L), eq(2), any()))
            .thenReturn(new MinutesRenderResponse(
                12L,
                2,
                MinutesStatus.FINAL,
                template(),
                "<!DOCTYPE html><html></html>",
                true
            ));

        mockMvc.perform(post("/api/meetings/12/minutes/versions/2/render")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("templateCode", "CUSC_STANDARD")
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meetingId").value(12))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("FINAL"))
            .andExpect(jsonPath("$.template.code").value("CUSC_STANDARD"))
            .andExpect(jsonPath("$.html").value("<!DOCTYPE html><html></html>"))
            .andExpect(jsonPath("$.canExportPdf").value(true));
    }

    @Test
    void renderMissingTemplateCodeReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/meetings/12/minutes/versions/2/render")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void renderInvalidTemplateCodeReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/meetings/12/minutes/versions/2/render")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateCode\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exportPdfValidRequestReturnsAttachment() throws Exception {
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        when(pdfService.exportPdf(eq(12L), eq(2), any())).thenReturn(pdf);

        mockMvc.perform(post("/api/meetings/12/minutes/versions/2/export/pdf")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("templateCode", "CUSC_STANDARD")
                )))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(
                HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.containsString("attachment")
            ))
            .andExpect(header().string(
                HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.containsString("bien-ban-hop-12-v2.pdf")
            ))
            .andExpect(content().bytes(pdf));
    }

    @Test
    void exportPdfMissingTemplateCodeReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/meetings/12/minutes/versions/2/export/pdf")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    private MinutesTemplateResponse template() {
        return new MinutesTemplateResponse(
            "CUSC_STANDARD",
            "Biên bản họp CUSC",
            "Mẫu biên bản họp CUSC - BM07.40/PM/CUSC"
        );
    }
}
