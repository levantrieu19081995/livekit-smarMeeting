package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import vn.cusc.meetingservice.dto.response.AccountSummaryResponse;
import vn.cusc.meetingservice.dto.response.MeetingDocumentFileResponse;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.model.enums.MeetingDocumentSource;
import vn.cusc.meetingservice.service.MeetingDocumentService;

class MeetingDocumentControllerTest {

    private final MeetingDocumentService documentService = Mockito.mock(MeetingDocumentService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MeetingDocumentController(documentService)).build();
    }

    @Test
    void uploadDocumentAcceptsRealMultipartFile() throws Exception {
        byte[] bytes = "pdf-content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.pdf",
            "application/pdf",
            bytes
        );
        when(documentService.uploadDocument(eq(10L), Mockito.any(MultipartFile.class)))
            .thenReturn(new MeetingDocumentResponse(
                7L,
                "test.pdf",
                "stored-test.pdf",
                "application/pdf",
                (long) bytes.length,
                "meetings/10/stored-test.pdf",
                LocalDateTime.now(),
                10L,
                new AccountSummaryResponse("account-1", "Uploader"),
                MeetingDocumentSource.IN_MEETING
            ));

        mockMvc.perform(multipart("/api/meetings/10/documents").file(file))
            .andExpect(status().isCreated());

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        Mockito.verify(documentService).uploadDocument(eq(10L), captor.capture());
        MultipartFile uploaded = captor.getValue();
        assertEquals("test.pdf", uploaded.getOriginalFilename());
        assertEquals("application/pdf", uploaded.getContentType());
        assertEquals(bytes.length, uploaded.getSize());
        assertArrayEquals(bytes, uploaded.getBytes());
    }

    @Test
    void viewAndDownloadUseCorrectDispositionAndContent() throws Exception {
        byte[] bytes = "document-content".getBytes();
        when(documentService.getDocumentFile(10L, 7L)).thenReturn(new MeetingDocumentFileResponse(
            new ByteArrayResource(bytes),
            "Ke hoach.pdf",
            "application/pdf",
            bytes.length
        ));

        mockMvc.perform(get("/api/meetings/10/documents/7/view"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("inline")))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes(bytes));

        mockMvc.perform(get("/api/meetings/10/documents/7/download"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment")))
            .andExpect(content().bytes(bytes));
    }

    @Test
    void legacyActiveDocumentCannotBeOpenedInlineEvenWithAMisleadingViewRoute() throws Exception {
        byte[] bytes = "<script>alert(1)</script>".getBytes();
        when(documentService.getDocumentFile(10L, 7L)).thenReturn(new MeetingDocumentFileResponse(
            new ByteArrayResource(bytes), "legacy.html", "text/html", bytes.length, false));
        mockMvc.perform(get("/api/meetings/10/documents/7/view"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox")))
            .andExpect(content().contentType("application/octet-stream"));
    }
}
