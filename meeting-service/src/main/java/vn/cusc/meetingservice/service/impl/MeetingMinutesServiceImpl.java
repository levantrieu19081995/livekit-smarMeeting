package vn.cusc.meetingservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import vn.cusc.meetingservice.dto.request.CreateMinutesVersionRequest;
import vn.cusc.meetingservice.dto.request.SaveMeetingMinutesRequest;
import vn.cusc.meetingservice.dto.request.UpdateMinutesDraftRequest;
import vn.cusc.meetingservice.dto.response.MeetingMinutesResponse;
import vn.cusc.meetingservice.dto.response.MinutesTranscriptEntryResponse;
import vn.cusc.meetingservice.dto.response.MinutesVersionResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingTranscriptChunkEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.MinutesNotFoundException;
import vn.cusc.meetingservice.mapper.MeetingMinutesMapper;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.MeetingTranscriptChunkRepository;
import vn.cusc.meetingservice.service.MeetingMinutesAuthorizationService;
import vn.cusc.meetingservice.service.MeetingMinutesService;

@Service
public class MeetingMinutesServiceImpl implements MeetingMinutesService {

    private static final int MAX_CONTENT_LENGTH = 1_000_000;

    private final MeetingRepository meetingRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingTranscriptChunkRepository chunkRepository;
    private final MeetingMinutesMapper minutesMapper;
    private final MeetingMinutesAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final vn.cusc.meetingservice.repository.MeetingConclusionRepository conclusionRepository;

    public MeetingMinutesServiceImpl(
        MeetingRepository meetingRepository,
        MeetingMinutesRepository minutesRepository,
        MeetingMinutesJobRepository jobRepository,
        MeetingTranscriptChunkRepository chunkRepository,
        MeetingMinutesMapper minutesMapper,
        MeetingMinutesAuthorizationService authorizationService,
        ObjectMapper objectMapper,
        vn.cusc.meetingservice.repository.MeetingConclusionRepository conclusionRepository
    ) {
        this.meetingRepository = meetingRepository;
        this.minutesRepository = minutesRepository;
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.minutesMapper = minutesMapper;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.conclusionRepository = conclusionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeetingMinutesResponse> getMinutes(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);
        return minutesRepository.findByMeetingIdOrderByVersionDesc(meetingId).stream()
            .map(minutesMapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MinutesTranscriptEntryResponse> getTranscript(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);
        return jobRepository.findFirstByMeetingIdOrderByAttemptDesc(meetingId)
            .filter(job -> job.getTranscriptStatus() == TranscriptPersistenceStatus.READY)
            .map(job -> chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(job.getId()).stream()
                .map(this::toTranscriptEntryResponse)
                .toList())
            .orElseGet(List::of);
    }

    @Override
    @Transactional
    public MeetingMinutesResponse saveMinutes(Long meetingId, SaveMeetingMinutesRequest request) {
        throw new AccessDeniedException("Legacy minutes endpoint is internal-only");
    }

    @Override
    @Transactional(readOnly = true)
    public MinutesVersionResponse getCurrent(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);
        return minutesRepository.findFirstByMeetingIdOrderByVersionDesc(meetingId)
            .map(minutesMapper::toVersionResponse)
            .orElseThrow(() -> new MinutesNotFoundException(meetingId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MinutesVersionResponse> getVersions(Long meetingId) {
        authorizationService.requireMeetingView(meetingId);
        return minutesRepository.findByMeetingIdOrderByVersionDesc(meetingId).stream()
            .map(minutesMapper::toVersionResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MinutesVersionResponse getVersion(Long meetingId, Integer version) {
        authorizationService.requireMeetingView(meetingId);
        return minutesMapper.toVersionResponse(findVersion(meetingId, version));
    }

    @Override
    @Transactional
    public MinutesVersionResponse createVersion(Long meetingId, CreateMinutesVersionRequest request) {
        MeetingEntity meeting = lockAndAuthorize(meetingId);
        Optional<MeetingMinutesEntity> latest =
            minutesRepository.findFirstByMeetingIdOrderByVersionDesc(meetingId);
        if (latest.isEmpty()) {
            throw new BusinessValidationException("Chua co phien ban bien ban ban dau");
        }
        if (request.baseVersion() == null) {
            throw new BusinessValidationException("baseVersion la bat buoc khi da co bien ban");
        }
        if (!latest.get().getVersion().equals(request.baseVersion())) {
            throw new BusinessValidationException("baseVersion khong phai version moi nhat");
        }

        MeetingMinutesEntity entity = new MeetingMinutesEntity();
        entity.setMeeting(meeting);
        entity.setVersion(latest.get().getVersion() + 1);
        entity.setStatus(MinutesStatus.DRAFT);
        entity.setSource(authorizationService.resolveVersionSource(meetingId));
        entity.setMinutesJson(serializeContent(request.content()));
        entity.setGeneratedAt(LocalDateTime.now());
        entity.setCreatedByAccount(authorizationService.requireCurrentAccount());
        MeetingMinutesEntity saved = minutesRepository.save(entity);
        syncConclusion(meetingId, request.content());

        return minutesMapper.toVersionResponse(saved);
    }

    @Override
    @Transactional
    public MinutesVersionResponse updateDraft(
        Long meetingId,
        Integer version,
        UpdateMinutesDraftRequest request
    ) {
        lockAndAuthorize(meetingId);
        MeetingMinutesEntity entity = findVersion(meetingId, version);
        if (request.expectedRevision() == null || !request.expectedRevision().equals(entity.getRevision())) {
            throw new BusinessValidationException("Ban nhap da thay doi. Vui long tai lai truoc khi luu.");
        }
        if (!entity.getId().equals(latest(meetingId).getId())) {
            throw new BusinessValidationException("Chi duoc cap nhat version moi nhat");
        }
        if (entity.getStatus() != MinutesStatus.DRAFT) {
            throw new BusinessValidationException("Bien ban FINAL la bat bien");
        }
        if (
            entity.getSource() == MinutesVersionSource.AI_GENERATED ||
            entity.getSource() == MinutesVersionSource.SYSTEM_GENERATED
        ) {
            throw new BusinessValidationException("Version generated la bat bien; hay tao version moi");
        }
        entity.setMinutesJson(serializeContent(request.content()));
        syncConclusion(meetingId, request.content());
        MeetingMinutesEntity saved = minutesRepository.save(entity);
        // The response must include the newly incremented optimistic revision, not the pre-flush value.
        minutesRepository.flush();
        return minutesMapper.toVersionResponse(saved);
    }

    @Override
    @Transactional
    public MinutesVersionResponse finalizeVersion(Long meetingId, Integer version) {
        lockAndAuthorize(meetingId);
        MeetingMinutesEntity entity = findVersion(meetingId, version);
        if (entity.getStatus() == MinutesStatus.FINAL) {
            return minutesMapper.toVersionResponse(entity);
        }
        if (!entity.getId().equals(latest(meetingId).getId())) {
            throw new BusinessValidationException("Chi duoc hoan tat version moi nhat");
        }
        if (entity.getStatus() != MinutesStatus.DRAFT) {
            throw new BusinessValidationException("Chi bien ban DRAFT moi co the hoan tat");
        }
        entity.setStatus(MinutesStatus.FINAL);
        entity.setFinalizedAt(LocalDateTime.now());
        MeetingMinutesEntity saved = minutesRepository.save(entity);
        minutesRepository.flush();
        return minutesMapper.toVersionResponse(saved);
    }

    private void syncConclusion(Long meetingId, JsonNode content) {
        conclusionRepository.findByMeetingId(meetingId).filter(c -> "GENERATED".equals(c.getStatus())).ifPresent(c -> {
            String edited = vn.cusc.meetingservice.service.ConclusionText.manual(content);
            if (!edited.equals(c.getFinalContent())) { c.setFinalContent(edited); c.setRevision(c.getRevision() + 1); }
        });
    }

    private MeetingEntity lockAndAuthorize(Long meetingId) {
        MeetingEntity meeting = meetingRepository.findByIdForUpdate(meetingId)
            .orElseThrow(() -> new MeetingNotFoundException(meetingId));
        authorizationService.requireMeetingAccess(meeting);
        return meeting;
    }

    private MeetingMinutesEntity findVersion(Long meetingId, Integer version) {
        return minutesRepository.findByMeetingIdAndVersion(meetingId, version)
            .orElseThrow(() -> new MinutesNotFoundException(meetingId, version));
    }

    private MeetingMinutesEntity latest(Long meetingId) {
        return minutesRepository.findFirstByMeetingIdOrderByVersionDesc(meetingId)
            .orElseThrow(() -> new MinutesNotFoundException(meetingId));
    }

    private String serializeContent(JsonNode content) {
        try {
            String serialized = objectMapper.writeValueAsString(content);
            if (serialized.length() > MAX_CONTENT_LENGTH) {
                throw new BusinessValidationException("Noi dung bien ban vuot qua gioi han cho phep");
            }
            return serialized;
        } catch (JsonProcessingException exception) {
            throw new BusinessValidationException("Noi dung bien ban khong hop le");
        }
    }

    private MinutesTranscriptEntryResponse toTranscriptEntryResponse(MeetingTranscriptChunkEntity chunk) {
        return new MinutesTranscriptEntryResponse(
            chunk.getId(),
            chunk.getAttendeeId(),
            chunk.getSpeakerName(),
            chunk.getAbsoluteStartTime(),
            chunk.getSegmentStartedAt(),
            chunk.getChunkStartMs(),
            chunk.getText()
        );
    }
}
