package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import vn.cusc.meetingservice.dto.request.RenderMinutesRequest;
import vn.cusc.meetingservice.dto.response.MinutesRenderResponse;
import vn.cusc.meetingservice.dto.response.MinutesTemplateResponse;
import vn.cusc.meetingservice.dto.template.MinutesTemplateModel;
import vn.cusc.meetingservice.dto.template.MinutesTemplateParticipant;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MinutesNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesTemplateCode;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;

@Service
public class MeetingMinutesTemplateService {

    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm");

    private final MeetingMinutesRepository minutesRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingMinutesAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final SpringTemplateEngine templateEngine;

    public MeetingMinutesTemplateService(
        MeetingMinutesRepository minutesRepository,
        MeetingParticipantRepository participantRepository,
        MeetingMinutesAuthorizationService authorizationService,
        ObjectMapper objectMapper,
        SpringTemplateEngine templateEngine
    ) {
        this.minutesRepository = minutesRepository;
        this.participantRepository = participantRepository;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.templateEngine = templateEngine;
    }

    public List<MinutesTemplateResponse> listTemplates() {
        authorizationService.requirePrincipal();
        return List.of(toTemplateResponse(MinutesTemplateCode.CUSC_STANDARD));
    }

    @Transactional(readOnly = true)
    public MinutesRenderResponse render(
        Long meetingId,
        Integer version,
        RenderMinutesRequest request
    ) {
        MeetingEntity meeting = authorizationService.requireMeetingView(meetingId);
        MeetingMinutesEntity minutes =
            minutesRepository.findByMeetingIdAndVersion(meetingId, version)
                .orElseThrow(() -> new MinutesNotFoundException(meetingId, version));
        JsonNode content = parseContent(minutes);
        MinutesTemplateCode templateCode = request.templateCode();
        MinutesTemplateModel model = buildModel(meeting, minutes, content);

        Context context = new Context();
        context.setVariable("model", model);
        String html = templateEngine.process(templateCode.getTemplateName(), context);

        return new MinutesRenderResponse(
            meetingId,
            version,
            minutes.getStatus(),
            toTemplateResponse(templateCode),
            html,
            minutes.getStatus() == MinutesStatus.FINAL
        );
    }

    private MinutesTemplateModel buildModel(
        MeetingEntity meeting,
        MeetingMinutesEntity minutes,
        JsonNode content
    ) {
        List<MeetingParticipantEntity> activeParticipants =
            participantRepository.findByMeetingId(meeting.getId()).stream()
                .filter(participant -> participant.getStatus() != ParticipantStatus.REMOVED)
                .sorted(Comparator.comparing(MeetingParticipantEntity::getId))
                .toList();

        List<MinutesTemplateParticipant> participants =
            java.util.stream.IntStream.range(0, activeParticipants.size())
                .mapToObj(index -> toParticipant(index + 1, activeParticipants.get(index)))
                .toList();

        return new MinutesTemplateModel(
            meeting.getId(),
            minutes.getVersion(),
            meeting.getMeetingCode(),
            meeting.getTitle(),
            meeting.getStartTime() == null ? "Chưa bắt đầu" : meeting.getStartTime().format(DATE_FORMAT),
            (meeting.getStartTime() == null ? "Chưa bắt đầu" : meeting.getStartTime().format(TIME_FORMAT))
                + " - "
                + (meeting.getEndTime() == null ? "Không đặt giờ kết thúc" : meeting.getEndTime().format(TIME_FORMAT)),
            "—",
            participants,
            firstParticipantName(activeParticipants, MeetingRole.CHAIRMAN),
            firstParticipantName(activeParticipants, MeetingRole.SECRETARY),
            textualContent(content, "mainContent"),
            textualContent(content, "conclusion"),
            minutes.getStatus() != MinutesStatus.FINAL
        );
    }

    private MinutesTemplateParticipant toParticipant(
        int order,
        MeetingParticipantEntity participant
    ) {
        return new MinutesTemplateParticipant(
            order,
            participant.getFullName(),
            roleText(participant.getRole())
        );
    }

    private String firstParticipantName(
        List<MeetingParticipantEntity> participants,
        MeetingRole role
    ) {
        return participants.stream()
            .filter(participant -> participant.getRole() == role)
            .map(MeetingParticipantEntity::getFullName)
            .findFirst()
            .orElse("");
    }

    private String roleText(MeetingRole role) {
        return switch (role) {
            case CHAIRMAN -> "Chủ tọa";
            case SECRETARY -> "Thư ký";
            case MEMBER -> "Thành viên";
            case GUEST -> "Khách mời";
        };
    }

    private String textualContent(JsonNode content, String fieldName) {
        JsonNode node = content.path(fieldName);
        return node.isTextual() ? node.asText() : "";
    }

    private JsonNode parseContent(MeetingMinutesEntity minutes) {
        try {
            return objectMapper.readTree(minutes.getMinutesJson());
        } catch (Exception exception) {
            throw new BusinessValidationException("Noi dung bien ban khong hop le");
        }
    }

    private MinutesTemplateResponse toTemplateResponse(MinutesTemplateCode code) {
        return new MinutesTemplateResponse(
            code.name(),
            code.getName(),
            code.getDescription()
        );
    }
}
