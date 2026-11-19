package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.cusc.meetingservice.exception.DocumentNotFoundException;
import vn.cusc.meetingservice.mapper.MeetingDocumentMapper;
import vn.cusc.meetingservice.model.enums.MeetingDocumentSource;
import vn.cusc.meetingservice.repository.MeetingDocumentRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.impl.MeetingDocumentServiceImpl;

@ExtendWith(MockitoExtension.class)
class MeetingDocumentServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingDocumentRepository documentRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private MeetingMinutesAuthorizationService authorizationService;

    private MeetingDocumentService service;

    @BeforeEach
    void setUp() {
        service = new MeetingDocumentServiceImpl(
            meetingRepository,
            documentRepository,
            fileStorageService,
            new MeetingDocumentMapper(),
            authorizationService
        );
    }

    @Test
    void documentMustBelongToMeetingPath() {
        when(documentRepository.findByIdAndMeetingId(7L, 10L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.getDocumentFile(10L, 7L));

        verify(authorizationService).requireMeetingView(10L);
        verify(documentRepository).findByIdAndMeetingId(7L, 10L);
    }

    @Test
    void documentSourceContractUsesInMeetingInsteadOfDuringMeeting() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertEquals("\"IN_MEETING\"", objectMapper.writeValueAsString(MeetingDocumentSource.IN_MEETING));
        assertEquals(
            MeetingDocumentSource.IN_MEETING,
            objectMapper.readValue("\"IN_MEETING\"", MeetingDocumentSource.class)
        );
        assertFalse(Arrays.stream(MeetingDocumentSource.values())
            .anyMatch(source -> "DURING_MEETING".equals(source.name())));
    }
}
