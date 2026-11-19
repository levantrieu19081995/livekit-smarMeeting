package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import vn.cusc.meetingservice.dto.request.SaveMeetingMinutesRequest;
import vn.cusc.meetingservice.dto.request.CreateMinutesVersionRequest;
import vn.cusc.meetingservice.dto.request.UpdateMinutesDraftRequest;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingTranscriptChunkEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.mapper.MeetingMinutesMapper;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesProcessingStage;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesVersionSource;
import vn.cusc.meetingservice.model.enums.TranscriptPersistenceStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.MeetingTranscriptChunkRepository;
import vn.cusc.meetingservice.service.impl.MeetingMinutesServiceImpl;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesVersionServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingMinutesRepository minutesRepository;
    @Mock private MeetingMinutesJobRepository jobRepository;
    @Mock private MeetingTranscriptChunkRepository chunkRepository;
    @Mock private MeetingMinutesAuthorizationService authorizationService;
    @Mock private vn.cusc.meetingservice.repository.MeetingConclusionRepository conclusionRepository;

    private MeetingMinutesService service;
    private MeetingEntity meeting;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MeetingMinutesServiceImpl(
            meetingRepository,
            minutesRepository,
            jobRepository,
            chunkRepository,
            new MeetingMinutesMapper(objectMapper),
            authorizationService,
            objectMapper, conclusionRepository
        );
        meeting = new MeetingEntity();
        meeting.setId(10L);
    }

    @Test
    void createVersionRejectsWhenInitialV1DoesNotExist() {
        lockMeeting();
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L))
            .thenReturn(Optional.empty());

        assertThrows(BusinessValidationException.class, () -> service.createVersion(
            10L,
            new CreateMinutesVersionRequest(null, objectMapper.createObjectNode())
        ));
    }

    @Test
    void existingVersionRejectsNullBaseVersion() {
        lockMeeting();
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L))
            .thenReturn(Optional.of(version(1, MinutesVersionSource.SECRETARY_EDITED, MinutesStatus.DRAFT)));

        assertThrows(BusinessValidationException.class, () -> service.createVersion(
            10L,
            new CreateMinutesVersionRequest(null, objectMapper.createObjectNode())
        ));
    }

    @Test
    void existingLatestRejectsStaleBaseVersion() {
        lockMeeting();
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L))
            .thenReturn(Optional.of(version(2, MinutesVersionSource.SECRETARY_EDITED, MinutesStatus.DRAFT)));

        assertThrows(BusinessValidationException.class, () -> service.createVersion(
            10L,
            new CreateMinutesVersionRequest(1, objectMapper.createObjectNode())
        ));
    }

    @Test
    void existingLatestBaseVersionCreatesNextVersion() {
        lockMeeting();
        MeetingMinutesEntity latest = version(2, MinutesVersionSource.SECRETARY_EDITED, MinutesStatus.DRAFT);
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L))
            .thenReturn(Optional.of(latest));
        when(authorizationService.resolveVersionSource(10L))
            .thenReturn(MinutesVersionSource.CHAIRMAN_EDITED);
        when(authorizationService.requireCurrentAccount()).thenReturn(account("chairman-1", "Chu tri"));
        when(minutesRepository.save(any())).thenAnswer(invocation -> {
            MeetingMinutesEntity saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });

        var response = service.createVersion(
            10L,
            new CreateMinutesVersionRequest(2, objectMapper.createObjectNode().put("title", "V3"))
        );

        assertEquals(3, response.version());
        assertEquals(MinutesVersionSource.CHAIRMAN_EDITED, response.source());
    }

    @Test
    void aiGeneratedV1CanBeBaseForSecretaryV2() {
        lockMeeting();
        MeetingMinutesEntity latest = version(1, MinutesVersionSource.AI_GENERATED, MinutesStatus.DRAFT);
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L))
            .thenReturn(Optional.of(latest));
        when(authorizationService.resolveVersionSource(10L))
            .thenReturn(MinutesVersionSource.SECRETARY_EDITED);
        when(authorizationService.requireCurrentAccount()).thenReturn(account("secretary-1", "Thu ky"));
        when(minutesRepository.save(any())).thenAnswer(invocation -> {
            MeetingMinutesEntity saved = invocation.getArgument(0);
            saved.setId(2L);
            return saved;
        });

        var response = service.createVersion(
            10L,
            new CreateMinutesVersionRequest(1, objectMapper.createObjectNode().put("title", "V2"))
        );

        assertEquals(2, response.version());
        assertEquals(MinutesVersionSource.SECRETARY_EDITED, response.source());
    }

    @Test
    void aiAndFinalVersionsAreImmutable() {
        lockMeeting();
        MeetingMinutesEntity ai = version(1, MinutesVersionSource.AI_GENERATED, MinutesStatus.DRAFT);
        when(minutesRepository.findByMeetingIdAndVersion(10L, 1)).thenReturn(Optional.of(ai));
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L)).thenReturn(Optional.of(ai));

        assertThrows(BusinessValidationException.class, () -> service.updateDraft(
            10L,
            1,
            new UpdateMinutesDraftRequest(objectMapper.createObjectNode())
        ));

        ai.setSource(MinutesVersionSource.SYSTEM_GENERATED);
        assertThrows(BusinessValidationException.class, () -> service.updateDraft(
            10L,
            1,
            new UpdateMinutesDraftRequest(objectMapper.createObjectNode())
        ));

        ai.setSource(MinutesVersionSource.SECRETARY_EDITED);
        ai.setStatus(MinutesStatus.FINAL);
        assertThrows(BusinessValidationException.class, () -> service.updateDraft(
            10L,
            1,
            new UpdateMinutesDraftRequest(objectMapper.createObjectNode())
        ));
    }

    @Test
    void editableDraftCanBeSavedAndFinalizedWithoutChangingContent() {
        lockMeeting();
        MeetingMinutesEntity draft = version(2, MinutesVersionSource.CHAIRMAN_EDITED, MinutesStatus.DRAFT);
        when(minutesRepository.findByMeetingIdAndVersion(10L, 2)).thenReturn(Optional.of(draft));
        when(minutesRepository.findFirstByMeetingIdOrderByVersionDesc(10L)).thenReturn(Optional.of(draft));
        when(minutesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateDraft(
            10L,
            2,
            new UpdateMinutesDraftRequest(objectMapper.createObjectNode().put("decision", "approved"))
        );
        String savedContent = draft.getMinutesJson();
        var finalized = service.finalizeVersion(10L, 2);

        assertEquals(MinutesStatus.FINAL, finalized.status());
        assertNotNull(finalized.finalizedAt());
        assertEquals(savedContent, draft.getMinutesJson());
    }

    @Test
    void legacySaveMinutesIsBlocked() {
        SaveMeetingMinutesRequest request = new SaveMeetingMinutesRequest(
            "transcript",
            objectMapper.createObjectNode().put("summary", "AI minutes")
        );

        assertThrows(AccessDeniedException.class, () -> service.saveMinutes(10L, request));
    }

    @Test
    void getTranscriptRequiresMeetingAccess() {
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.empty());

        service.getTranscript(10L);

        verify(authorizationService).requireMeetingView(10L);
    }

    @Test
    void getTranscriptReturnsEmptyWhenNoLatestJobExists() {
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.empty());

        assertEquals(List.of(), service.getTranscript(10L));
        verify(chunkRepository, never()).findByMinutesJob_IdOrderByResultOrderAsc(any());
    }

    @Test
    void getTranscriptReturnsEmptyForPendingProcessingOrFailedTranscriptStatus() {
        for (TranscriptPersistenceStatus status : List.of(
            TranscriptPersistenceStatus.PENDING,
            TranscriptPersistenceStatus.PROCESSING,
            TranscriptPersistenceStatus.FAILED
        )) {
            when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
                .thenReturn(Optional.of(job(20L, 2, status)));

            assertEquals(List.of(), service.getTranscript(10L));
        }

        verify(chunkRepository, never()).findByMinutesJob_IdOrderByResultOrderAsc(any());
    }

    @Test
    void getTranscriptReadsChunksForExactlyLatestReadyJobAndMapsPersistedFields() {
        MeetingMinutesJobEntity latest = job(22L, 2, TranscriptPersistenceStatus.READY);
        MeetingTranscriptChunkEntity second = chunk(
            102L,
            "account:user-2",
            "Speaker Two",
            "2026-09-08T08:00:03Z",
            "2026-09-08T08:00:00Z",
            3000L,
            " second text "
        );
        MeetingTranscriptChunkEntity first = chunk(
            101L,
            "account:user-1",
            "Speaker One",
            "2026-09-08T08:00:01Z",
            "2026-09-08T08:00:00Z",
            1000L,
            "first text"
        );
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.of(latest));
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(22L))
            .thenReturn(List.of(second, first));

        var response = service.getTranscript(10L);

        verify(chunkRepository).findByMinutesJob_IdOrderByResultOrderAsc(22L);
        assertEquals(2, response.size());
        assertEquals(102L, response.get(0).id());
        assertEquals("account:user-2", response.get(0).attendeeId());
        assertEquals("Speaker Two", response.get(0).speakerName());
        assertEquals("2026-09-08T08:00:03Z", response.get(0).absoluteStartTime());
        assertEquals("2026-09-08T08:00:00Z", response.get(0).segmentStartedAt());
        assertEquals(3000L, response.get(0).chunkStartMs());
        assertEquals(" second text ", response.get(0).text());
    }

    @Test
    void getTranscriptPreservesNullableFields() {
        MeetingMinutesJobEntity latest = job(22L, 2, TranscriptPersistenceStatus.READY);
        MeetingTranscriptChunkEntity chunk = chunk(101L, null, null, null, null, 0L, "text");
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L)).thenReturn(Optional.of(latest));
        when(chunkRepository.findByMinutesJob_IdOrderByResultOrderAsc(22L)).thenReturn(List.of(chunk));

        var response = service.getTranscript(10L);

        assertEquals(null, response.get(0).attendeeId());
        assertEquals(null, response.get(0).speakerName());
        assertEquals(null, response.get(0).absoluteStartTime());
        assertEquals(null, response.get(0).segmentStartedAt());
    }

    @Test
    void getTranscriptDoesNotFallbackToOlderReadyJobWhenLatestIsNotReady() {
        when(jobRepository.findFirstByMeetingIdOrderByAttemptDesc(10L))
            .thenReturn(Optional.of(job(22L, 2, TranscriptPersistenceStatus.PROCESSING)));

        assertEquals(List.of(), service.getTranscript(10L));
        verify(chunkRepository, never()).findByMinutesJob_IdOrderByResultOrderAsc(any());
    }

    private void lockMeeting() {
        when(meetingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(meeting));
    }

    private MeetingMinutesEntity version(
        int number,
        MinutesVersionSource source,
        MinutesStatus status
    ) {
        MeetingMinutesEntity entity = new MeetingMinutesEntity();
        entity.setId((long) number);
        entity.setMeeting(meeting);
        entity.setVersion(number);
        entity.setSource(source);
        entity.setStatus(status);
        entity.setMinutesJson("{}");
        return entity;
    }

    private MeetingMinutesJobEntity job(
        Long id,
        int attempt,
        TranscriptPersistenceStatus transcriptStatus
    ) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setId(id);
        job.setMeeting(meeting);
        job.setStatus(MinutesJobStatus.PROCESSING);
        job.setStage(MinutesProcessingStage.BUILDING_TRANSCRIPT);
        job.setAttempt(attempt);
        job.setProgressPercent(50);
        job.setRequestedByAccount(account("requester", "Requester"));
        job.setTranscriptStatus(transcriptStatus);
        return job;
    }

    private MeetingTranscriptChunkEntity chunk(
        Long id,
        String attendeeId,
        String speakerName,
        String absoluteStartTime,
        String segmentStartedAt,
        Long chunkStartMs,
        String text
    ) {
        MeetingTranscriptChunkEntity chunk = new MeetingTranscriptChunkEntity();
        chunk.setId(id);
        chunk.setAttendeeId(attendeeId);
        chunk.setSpeakerName(speakerName);
        chunk.setAbsoluteStartTime(absoluteStartTime);
        chunk.setSegmentStartedAt(segmentStartedAt);
        chunk.setChunkStartMs(chunkStartMs);
        chunk.setText(text);
        return chunk;
    }

    private UserAccountEntity account(String publicId, String fullName) {
        UserAccountEntity account = new UserAccountEntity();
        account.setPublicId(publicId);
        account.setFullName(fullName);
        return account;
    }
}
