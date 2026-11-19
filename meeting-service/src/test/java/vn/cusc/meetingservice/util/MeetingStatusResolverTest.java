package vn.cusc.meetingservice.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;

class MeetingStatusResolverTest {

    private final MeetingStatusResolver resolver = new MeetingStatusResolver();
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 8, 12, 0);

    @Test
    void clockCorrectionCannotUndoExplicitInstantOrLaterStart() {
        for (var mode : new MeetingCreationMode[]{MeetingCreationMode.INSTANT, MeetingCreationMode.LATER}) {
            var started = meeting(MeetingStatus.IN_PROGRESS, now.plusMinutes(5), null);
            started.setCreationMode(mode);
            assertEquals(MeetingStatus.IN_PROGRESS, resolver.resolve(started, now));
            started.setStatus(MeetingStatus.COMPLETED);
            assertEquals(MeetingStatus.COMPLETED, resolver.resolve(started, now));
            started.setStatus(MeetingStatus.CANCELLED);
            assertEquals(MeetingStatus.CANCELLED, resolver.resolve(started, now));
            var waiting = meeting(MeetingStatus.UPCOMING, null, null);
            waiting.setCreationMode(mode);
            assertEquals(MeetingStatus.UPCOMING, resolver.resolve(waiting, now));
        }
        var point = meeting(MeetingStatus.IN_PROGRESS, now.plusMinutes(5), now.plusHours(1));
        point.setCreationMode(MeetingCreationMode.POINT);
        assertEquals(MeetingStatus.UPCOMING, resolver.resolve(point, now));
    }

    @Test
    void noEndTimeDoesNotExpireAndUnscheduledLinkWaitsForFirstJoin() {
        assertEquals(MeetingStatus.IN_PROGRESS, resolver.resolve(meeting(MeetingStatus.IN_PROGRESS, now.minusYears(1), null), now));
        assertEquals(MeetingStatus.UPCOMING, resolver.resolve(meeting(MeetingStatus.UPCOMING, null, null), now));
    }

    @Test
    void cancelledStaysCancelledRegardlessOfSchedule() {
        assertEquals(MeetingStatus.CANCELLED, resolver.resolve(meeting(
            MeetingStatus.CANCELLED,
            now.minusDays(1),
            now.minusHours(1)
        ), now));
    }

    @Test
    void completedStaysCompletedRegardlessOfSchedule() {
        assertEquals(MeetingStatus.COMPLETED, resolver.resolve(meeting(
            MeetingStatus.COMPLETED,
            now.plusHours(1),
            now.plusHours(2)
        ), now));
    }

    @Test
    void nonTerminalBeforeStartResolvesUpcoming() {
        assertEquals(MeetingStatus.UPCOMING, resolver.resolve(meeting(
            MeetingStatus.IN_PROGRESS,
            now.plusMinutes(1),
            now.plusHours(1)
        ), now));
    }

    @Test
    void nonTerminalExactlyAtStartResolvesInProgress() {
        assertEquals(MeetingStatus.IN_PROGRESS, resolver.resolve(meeting(
            MeetingStatus.UPCOMING,
            now,
            now.plusHours(1)
        ), now));
    }

    @Test
    void nonTerminalBetweenStartAndEndResolvesInProgress() {
        assertEquals(MeetingStatus.IN_PROGRESS, resolver.resolve(meeting(
            MeetingStatus.UPCOMING,
            now.minusHours(1),
            now.plusHours(1)
        ), now));
    }

    @Test
    void nonTerminalExactlyAtEndResolvesInProgress() {
        assertEquals(MeetingStatus.IN_PROGRESS, resolver.resolve(meeting(
            MeetingStatus.IN_PROGRESS,
            now.minusHours(1),
            now
        ), now));
    }

    @Test
    void nonTerminalAfterEndResolvesCompleted() {
        assertEquals(MeetingStatus.COMPLETED, resolver.resolve(meeting(
            MeetingStatus.IN_PROGRESS,
            now.minusHours(2),
            now.minusNanos(1)
        ), now));
    }

    private MeetingEntity meeting(
        MeetingStatus status,
        LocalDateTime startTime,
        LocalDateTime endTime
    ) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setStatus(status);
        meeting.setStartTime(startTime);
        meeting.setEndTime(endTime);
        return meeting;
    }
}
