package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import vn.cusc.meetingservice.config.FrontendProperties;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.mapper.MeetingMapper;
import vn.cusc.meetingservice.mapper.MeetingParticipantMapper;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.SystemRole;
import vn.cusc.meetingservice.service.impl.MeetingServiceImpl;
import vn.cusc.meetingservice.util.MeetingCodeGenerator;
import vn.cusc.meetingservice.util.MeetingStatusResolver;
import vn.cusc.meetingservice.util.RoomNameGenerator;

@DataJpaTest
@Import({
    MeetingServiceImpl.class,
    MeetingMapper.class,
    MeetingParticipantMapper.class,
    MeetingStatusResolver.class,
    MeetingCodeGenerator.class,
    RoomNameGenerator.class
})
@EnableConfigurationProperties({FrontendProperties.class, LiveKitProperties.class})
@TestPropertySource(properties = {
    "frontend.base-url=http://localhost:3000",
    "livekit.url=ws://localhost:7880"
})
@ActiveProfiles("test")
class MeetingStatusFilteringIntegrationTest {
    @org.springframework.boot.test.mock.mockito.MockBean
    private MeetingSpeakingService meetingSpeakingService;

    @Autowired private MeetingService meetingService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private EntityManager entityManager;

    @MockBean private MeetingParticipantRepository meetingParticipantRepository;
    @MockBean private MeetingParticipantConnectionRepository connectionRepository;
    @MockBean private UserAccountRepository userAccountRepository;
    @MockBean private UserAccountService userAccountService;
    @MockBean private CurrentUserService currentUserService;
    @MockBean private LiveKitService liveKitService;
    @MockBean private MeetingAccessPolicyService meetingAccessPolicyService;
    @MockBean private MeetingCaptureService meetingCaptureService;
    @MockBean private MeetingPollTerminalService meetingPollTerminalService;
    @MockBean private MeetingMediaCleanupService mediaCleanup;
    @MockBean private MeetingGuestIdentityService guestIdentities;
    @MockBean private MeetingFileCleanupService fileCleanup;

    @BeforeEach
    void authenticateListViewer() {
        when(currentUserService.getCurrentPrincipal()).thenReturn(Optional.of(new MeetingPrincipal(
            99L, "status-viewer", "status-viewer", "status-viewer@example.test", "Status viewer", null,
            AccountStatus.ACTIVE, SystemRole.USER, true, AuthProvider.LOCAL, null
        )));
    }

    @Test
    void startedOpenMeetingsRemainInProgressIfWallClockIsBehindTheirStart() {
        for (var mode : new vn.cusc.meetingservice.model.enums.MeetingCreationMode[]{
            vn.cusc.meetingservice.model.enums.MeetingCreationMode.INSTANT,
            vn.cusc.meetingservice.model.enums.MeetingCreationMode.LATER}) {
            var value = saveMeeting("CLOCK-" + mode, "Started " + mode, MeetingStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), null, MeetingAccessType.PUBLIC);
            value.setCreationMode(mode);
        }
        entityManager.flush(); entityManager.clear();
        var active = meetingService.getMeetings(null, MeetingStatus.IN_PROGRESS, null, null, null, PageRequest.of(0, 20));
        assertEquals(2, active.totalElements());
        assertEveryResponseStatus(active.content(), MeetingStatus.IN_PROGRESS);
        assertEquals(0, meetingService.getMeetings(null, MeetingStatus.UPCOMING, null, null, null, PageRequest.of(0, 20)).totalElements());
    }

    @Test
    void statusFilterUsesEffectiveStatusAndPreservesPersistedStatus() {
        LocalDateTime now = LocalDateTime.now();
        MeetingEntity activePersistedUpcoming = saveMeeting(
            "MTG-I5-ACTIVE-UPCOMING",
            "Active persisted upcoming",
            MeetingStatus.UPCOMING,
            now.minusHours(1),
            now.plusHours(1),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-PAST-UPCOMING",
            "Past persisted upcoming",
            MeetingStatus.UPCOMING,
            now.minusHours(4),
            now.minusHours(3),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-PAST-IN-PROGRESS",
            "Past persisted in progress",
            MeetingStatus.IN_PROGRESS,
            now.minusHours(5),
            now.minusHours(4),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-COMPLETED-FUTURE",
            "Completed future",
            MeetingStatus.COMPLETED,
            now.plusHours(1),
            now.plusHours(2),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-CANCELLED-PAST",
            "Cancelled past",
            MeetingStatus.CANCELLED,
            now.minusHours(6),
            now.minusHours(5),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-FUTURE-UPCOMING",
            "Future upcoming",
            MeetingStatus.UPCOMING,
            now.plusHours(3),
            now.plusHours(4),
            MeetingAccessType.PUBLIC
        );
        entityManager.flush();
        entityManager.clear();

        var inProgress = meetingService.getMeetings(null, MeetingStatus.IN_PROGRESS, null, null, null, PageRequest.of(0, 20));
        assertIds(inProgress.content(), List.of(activePersistedUpcoming.getId()));
        assertEveryResponseStatus(inProgress.content(), MeetingStatus.IN_PROGRESS);

        var upcoming = meetingService.getMeetings(null, MeetingStatus.UPCOMING, null, null, null, PageRequest.of(0, 20));
        assertEquals(1, upcoming.totalElements());
        assertEveryResponseStatus(upcoming.content(), MeetingStatus.UPCOMING);
        assertTrue(upcoming.content().stream().noneMatch(item -> item.id().equals(activePersistedUpcoming.getId())));

        var completed = meetingService.getMeetings(null, MeetingStatus.COMPLETED, null, null, null, PageRequest.of(0, 20));
        assertEquals(3, completed.totalElements());
        assertEveryResponseStatus(completed.content(), MeetingStatus.COMPLETED);
        assertTrue(completed.content().stream().noneMatch(item -> item.meetingCode().equals("MTG-I5-CANCELLED-PAST")));

        var cancelled = meetingService.getMeetings(null, MeetingStatus.CANCELLED, null, null, null, PageRequest.of(0, 20));
        assertEquals(1, cancelled.totalElements());
        assertEveryResponseStatus(cancelled.content(), MeetingStatus.CANCELLED);

        entityManager.flush();
        entityManager.clear();
        MeetingEntity reloaded = meetingRepository.findById(activePersistedUpcoming.getId()).orElseThrow();
        assertEquals(MeetingStatus.UPCOMING, reloaded.getStatus());
    }

    @Test
    void effectiveStatusFilterPreservesDatabasePaginationAndCombinesWithExistingFilters() {
        LocalDateTime now = LocalDateTime.now();
        saveMeeting(
            "MTG-I5-ALPHA-1",
            "Alpha planning",
            MeetingStatus.UPCOMING,
            now.minusHours(1),
            now.plusHours(1),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-ALPHA-2",
            "Alpha review",
            MeetingStatus.IN_PROGRESS,
            now.minusHours(2),
            now.plusHours(2),
            MeetingAccessType.PUBLIC
        );
        saveMeeting(
            "MTG-I5-BETA",
            "Beta private",
            MeetingStatus.UPCOMING,
            now.minusHours(1),
            now.plusHours(1),
            MeetingAccessType.PRIVATE
        );
        saveMeeting(
            "MTG-I5-FUTURE",
            "Alpha future",
            MeetingStatus.UPCOMING,
            now.plusHours(1),
            now.plusHours(2),
            MeetingAccessType.PUBLIC
        );
        entityManager.flush();
        entityManager.clear();

        var page = meetingService.getMeetings(
            "Alpha",
            MeetingStatus.IN_PROGRESS,
            MeetingAccessType.PUBLIC,
            LocalDate.now().minusDays(1).toString(),
            LocalDate.now().plusDays(1).toString(),
            PageRequest.of(0, 1)
        );

        assertEquals(2, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(1, page.content().size());
        assertEveryResponseStatus(page.content(), MeetingStatus.IN_PROGRESS);
    }

    private MeetingEntity saveMeeting(String meetingCode,
                                      String title,
                                      MeetingStatus status,
                                      LocalDateTime startTime,
                                      LocalDateTime endTime,
                                      MeetingAccessType accessType) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setMeetingCode(meetingCode);
        meeting.setTitle(title);
        meeting.setRoomName("room-" + meetingCode.toLowerCase());
        meeting.setStartTime(startTime);
        meeting.setEndTime(endTime);
        meeting.setStatus(status);
        meeting.setAccessType(accessType);
        return meetingRepository.save(meeting);
    }

    private void assertIds(List<MeetingResponse> responses, List<Long> expectedIds) {
        assertEquals(expectedIds, responses.stream().map(MeetingResponse::id).toList());
    }

    private void assertEveryResponseStatus(List<MeetingResponse> responses, MeetingStatus expectedStatus) {
        assertTrue(responses.stream().allMatch(response -> response.status() == expectedStatus));
    }
}
