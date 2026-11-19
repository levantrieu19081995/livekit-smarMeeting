package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingRepository;

/** Scheduled meetings already become read-only at their deadline; reconcile their external resources too. */
@Service
public class MeetingTerminalRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingTerminalRecoveryService.class);
    private final MeetingRepository meetings;
    private final MeetingCaptureService capture;
    private final MeetingPollTerminalService polls;
    private final MeetingSpeakingService speaking;
    private final MeetingMediaCleanupService media;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private long cursor;

    public MeetingTerminalRecoveryService(MeetingRepository meetings, MeetingCaptureService capture,
        MeetingPollTerminalService polls, MeetingSpeakingService speaking, MeetingMediaCleanupService media,
        PlatformTransactionManager manager, @Value("${meeting.terminal-recovery-enabled:true}") boolean enabled) {
        this.meetings = meetings; this.capture = capture; this.polls = polls; this.speaking = speaking;
        this.media = media; this.enabled = enabled; transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${meeting.terminal-recovery-interval-ms:30000}", initialDelayString = "${meeting.terminal-recovery-initial-delay-ms:30000}")
    public void recoverBatch() {
        if (!enabled) return;
        var now = LocalDateTime.now();
        var batch = meetings.findAll((root, query, cb) -> cb.and(
            cb.greaterThan(root.get("id"), cursor), root.get("status").in(MeetingStatus.UPCOMING, MeetingStatus.IN_PROGRESS),
            cb.lessThan(root.get("endTime"), now)), PageRequest.of(0, 20, Sort.by("id"))).getContent();
        if (batch.isEmpty()) { cursor = 0; return; }
        for (var candidate : batch) {
            cursor = candidate.getId();
            try { recoverExpiredMeeting(candidate.getId()); }
            catch (RuntimeException failure) { LOG.warn("Scheduled terminal recovery will retry: meetingId={}", candidate.getId()); }
        }
    }

    public void recoverExpiredMeeting(Long meetingId) {
        transaction.executeWithoutResult(status -> meetings.findByIdForUpdate(meetingId).ifPresent(meeting -> {
            if (meeting.getStatus() == MeetingStatus.COMPLETED || meeting.getStatus() == MeetingStatus.CANCELLED
                || meeting.getEndTime() == null || !meeting.getEndTime().isBefore(LocalDateTime.now())) return;
            capture.finalizeForTerminalTransition(meetingId);
            polls.closeForTerminalTransition(meetingId);
            speaking.finalizeForTerminalTransition(meetingId);
            meeting.setStatus(MeetingStatus.COMPLETED);
            meetings.save(meeting);
            media.enqueue(meetingId, "DELETE_ROOM", meeting.getRoomName(), null);
        }));
    }
}
