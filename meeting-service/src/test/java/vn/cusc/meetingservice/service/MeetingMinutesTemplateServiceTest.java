package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import vn.cusc.meetingservice.dto.request.RenderMinutesRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesTemplateCode;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesTemplateServiceTest {

    @Mock private MeetingMinutesRepository minutesRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private MeetingMinutesAuthorizationService authorizationService;

    private ObjectMapper objectMapper;
    private MeetingMinutesTemplateService service;
    private MeetingEntity meeting;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MeetingMinutesTemplateService(
            minutesRepository,
            participantRepository,
            authorizationService,
            objectMapper,
            templateEngine()
        );
        meeting = meeting();
    }

    @Test
    void listTemplatesReturnsExactlyCuscStandard() {
        var templates = service.listTemplates();

        assertEquals(1, templates.size());
        assertEquals("CUSC_STANDARD", templates.get(0).code());
        assertEquals("Biên bản họp CUSC", templates.get(0).name());
        assertEquals("Mẫu biên bản họp CUSC - BM07.40/PM/CUSC", templates.get(0).description());
    }

    @Test
    void renderRequiresMeetingAccess() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of());

        service.render(12L, 2, request());

        verify(authorizationService).requireMeetingView(12L);
    }

    @Test
    void renderLoadsRequestedVersionByMeetingIdAndVersion() {
        stubRender(minutes(2, MinutesStatus.FINAL, "version 2", "done"), List.of());

        service.render(12L, 2, request());

        verify(minutesRepository).findByMeetingIdAndVersion(12L, 2);
    }

    @Test
    void renderUsesVersionTwoContentWithoutLatestFallback() {
        stubRender(minutes(2, MinutesStatus.FINAL, "Only version 2 content", "done"), List.of());

        var response = service.render(12L, 2, request());

        assertTrue(response.html().contains("Only version 2 content"));
    }

    @Test
    void renderMapsMeetingTitleAndTimeFields() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of());

        String html = service.render(12L, 2, request()).html();

        assertTrue(html.contains("Họp triển khai"));
        assertTrue(html.contains("26/08/2026"));
        assertTrue(html.contains("08:30 - 10:15"));
        assertTrue(html.contains("Địa điểm:</strong> <span>—</span>"));
    }

    @Test
    void renderFiltersRemovedParticipantsAndSortsById() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of(
            participant(3L, "C", MeetingRole.MEMBER, ParticipantStatus.JOINED),
            participant(1L, "A", MeetingRole.CHAIRMAN, ParticipantStatus.INVITED),
            participant(2L, "B", MeetingRole.SECRETARY, ParticipantStatus.REMOVED)
        ));

        String html = service.render(12L, 2, request()).html();

        assertTrue(html.indexOf(">A<") < html.indexOf(">C<"));
        assertFalse(html.contains(">B<"));
    }

    @Test
    void renderMapsRolesChairmanSecretaryMemberGuest() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of(
            participant(1L, "A", MeetingRole.CHAIRMAN, ParticipantStatus.JOINED),
            participant(2L, "B", MeetingRole.SECRETARY, ParticipantStatus.JOINED),
            participant(3L, "C", MeetingRole.MEMBER, ParticipantStatus.JOINED),
            participant(4L, "D", MeetingRole.GUEST, ParticipantStatus.JOINED)
        ));

        String html = service.render(12L, 2, request()).html();

        assertTrue(html.contains("Chủ tọa"));
        assertTrue(html.contains("Thư ký"));
        assertTrue(html.contains("Thành viên"));
        assertTrue(html.contains("Khách mời"));
    }

    @Test
    void renderMapsChairmanAndSecretaryNames() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of(
            participant(1L, "Chair", MeetingRole.CHAIRMAN, ParticipantStatus.JOINED),
            participant(2L, "Secretary", MeetingRole.SECRETARY, ParticipantStatus.JOINED)
        ));

        String html = service.render(12L, 2, request()).html();

        assertTrue(html.contains("<div class=\"signature-name\">Secretary</div>"));
        assertTrue(html.contains("<div class=\"signature-name\">Chair</div>"));
    }

    @Test
    void renderPreservesMainContentTextAndNewline() {
        stubRender(minutes(2, MinutesStatus.FINAL, "Dòng 1\nDòng 2", "done"), List.of());

        String html = service.render(12L, 2, request()).html();

        assertTrue(html.contains("white-space: pre-wrap"));
        assertTrue(html.contains("Dòng 1\nDòng 2"));
    }

    @Test
    void renderMapsConclusionAndBlankConclusionFallback() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "Kết luận"), List.of());
        assertTrue(service.render(12L, 2, request()).html().contains("Kết luận"));

        stubRender(minutes(2, MinutesStatus.FINAL, "main", ""), List.of());
        assertTrue(service.render(12L, 2, request()).html().contains("<div class=\"prewrap\">—</div>"));
    }

    @Test
    void draftRendersWatermarkAndFinalDoesNot() {
        stubRender(minutes(2, MinutesStatus.DRAFT, "main", "done"), List.of());
        assertTrue(service.render(12L, 2, request()).html().contains("BẢN NHÁP"));

        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of());
        assertFalse(service.render(12L, 2, request()).html().contains("BẢN NHÁP"));
    }

    @Test
    void maliciousContentIsEscaped() {
        stubRender(minutes(2, MinutesStatus.FINAL, "<script>alert(1)</script>", "done"), List.of());

        String html = service.render(12L, 2, request()).html();

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void draftResponseCanExportPdfIsFalseAndServiceDoesNotSave() {
        stubRender(minutes(2, MinutesStatus.DRAFT, "main", "done"), List.of());

        var response = service.render(12L, 2, request());

        assertFalse(response.canExportPdf());
        verify(minutesRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    void finalResponseCanExportPdfIsTrueAndServiceDoesNotSave() {
        stubRender(minutes(2, MinutesStatus.FINAL, "main", "done"), List.of());

        var response = service.render(12L, 2, request());

        assertTrue(response.canExportPdf());
        verify(minutesRepository, never()).save(org.mockito.Mockito.any());
    }

    private void stubRender(
        MeetingMinutesEntity minutes,
        List<MeetingParticipantEntity> participants
    ) {
        when(authorizationService.requireMeetingView(12L)).thenReturn(meeting);
        when(minutesRepository.findByMeetingIdAndVersion(12L, 2))
            .thenReturn(Optional.of(minutes));
        when(participantRepository.findByMeetingId(12L)).thenReturn(participants);
    }

    private RenderMinutesRequest request() {
        return new RenderMinutesRequest(MinutesTemplateCode.CUSC_STANDARD);
    }

    private MeetingEntity meeting() {
        MeetingEntity entity = new MeetingEntity();
        entity.setId(12L);
        entity.setMeetingCode("MTG-20260826-0001");
        entity.setTitle("Họp triển khai");
        entity.setDescription("Mô tả");
        entity.setRoomName("technical-room");
        entity.setRoomSid("RM_1");
        entity.setStartTime(LocalDateTime.of(2026, 8, 26, 8, 30));
        entity.setEndTime(LocalDateTime.of(2026, 8, 26, 10, 15));
        entity.setStatus(MeetingStatus.COMPLETED);
        entity.setAccessType(MeetingAccessType.PRIVATE);
        return entity;
    }

    private MeetingMinutesEntity minutes(
        int version,
        MinutesStatus status,
        String mainContent,
        String conclusion
    ) {
        MeetingMinutesEntity entity = new MeetingMinutesEntity();
        entity.setId((long) version);
        entity.setMeeting(meeting);
        entity.setVersion(version);
        entity.setStatus(status);
        entity.setSource(MinutesVersionSource.SYSTEM_GENERATED);
        entity.setMinutesJson(objectMapper.createObjectNode()
            .put("mainContent", mainContent)
            .put("conclusion", conclusion)
            .toString());
        entity.setGeneratedAt(LocalDateTime.now());
        return entity;
    }

    private MeetingParticipantEntity participant(
        Long id,
        String fullName,
        MeetingRole role,
        ParticipantStatus status
    ) {
        MeetingParticipantEntity entity = new MeetingParticipantEntity();
        entity.setId(id);
        entity.setMeeting(meeting);
        entity.setParticipantKey("pk-" + id);
        entity.setUserId("user-" + id);
        entity.setFullName(fullName);
        entity.setEmail("user" + id + "@cusc.vn");
        entity.setRole(role);
        entity.setStatus(status);
        entity.setJoinType(ParticipantJoinType.AUTHENTICATED);
        return entity;
    }

    private SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
