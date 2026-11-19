package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.repository.MeetingMediaCleanupJobRepository;

/** Only the job row is locked during bounded remote I/O; never a meeting row. */
@Service
public class MeetingMediaCleanupWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingMediaCleanupWorker.class);
    private final MeetingMediaCleanupJobRepository jobs;
    private final LiveKitService liveKit;
    private final TransactionTemplate transaction;
    private final vn.cusc.meetingservice.repository.UserAccountRepository accounts;
    private final vn.cusc.meetingservice.repository.MeetingRepository meetings;
    public MeetingMediaCleanupWorker(MeetingMediaCleanupJobRepository jobs, LiveKitService liveKit,
        PlatformTransactionManager manager, vn.cusc.meetingservice.repository.UserAccountRepository accounts,
        vn.cusc.meetingservice.repository.MeetingRepository meetings) {
        this.jobs = jobs; this.liveKit = liveKit;
        this.accounts = accounts; this.meetings = meetings;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Scheduled(fixedDelayString = "${livekit.cleanup.interval-ms:5000}")
    public void recoverOne() {
        jobs.findFirstByNextAttemptAtLessThanEqualOrderByIdAsc(LocalDateTime.now(java.time.ZoneOffset.UTC))
            .ifPresent(job -> process(job.getId()));
    }
    public void process(Long id) {
        transaction.executeWithoutResult(status -> jobs.findByIdForUpdate(id).ifPresent(job -> {
            if (job.getNextAttemptAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC))) return;
            try {
                switch (job.getOperation()) {
                    case "DELETE_ROOM" -> liveKit.deleteRoom(job.getRoomName());
                    case "DELETE_ORPHAN_ROOM" -> {
                        // Same lock as idempotent creation: a queued compensation cannot delete a successful retry.
                        if (job.getCreatorAccountId() != null) accounts.findByIdForUpdate(job.getCreatorAccountId());
                        if (!meetings.existsByRoomName(job.getRoomName())) liveKit.deleteRoom(job.getRoomName());
                    }
                    case "REMOVE_PARTICIPANT" -> liveKit.removeParticipant(job.getRoomName(), job.getTargetIdentity());
                    case "REMOVE_ATTENDEE" -> liveKit.removeParticipantsByAttendeeId(job.getRoomName(), job.getTargetIdentity());
                    default -> throw new IllegalStateException("Unsupported media cleanup operation");
                }
                jobs.delete(job);
            } catch (RuntimeException failure) {
                job.setAttempts(Math.min(1000000, job.getAttempts() + 1));
                job.setLastErrorCode("LIVEKIT_CLEANUP_UNAVAILABLE");
                job.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(Math.min(300, 1L << Math.min(8, job.getAttempts()))));
                jobs.save(job);
                LOG.warn("Media cleanup remains pending: jobId={}, operation={}, attempts={}",
                    job.getId(), job.getOperation(), job.getAttempts());
            }
        }));
    }
}
