package vn.cusc.meetingservice.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cusc.meetingservice.repository.MeetingMediaCleanupJobRepository;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;

@Service
public class MeetingMediaCleanupService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MeetingMediaCleanupService.class);
    private final JdbcTemplate jdbc;
    private final MeetingMediaCleanupWorker worker;
    private final MeetingMediaCleanupJobRepository jobs;
    private final org.springframework.transaction.support.TransactionTemplate compensation;
    public MeetingMediaCleanupService(JdbcTemplate jdbc, MeetingMediaCleanupWorker worker, MeetingMediaCleanupJobRepository jobs,
        org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.worker = worker; this.jobs = jobs;
        compensation = new org.springframework.transaction.support.TransactionTemplate(manager);
        compensation.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional
    public void enqueue(Long meetingId, String operation, String roomName, String identity) {
        insert(meetingId, null, operation, roomName, identity, true);
    }
    public void compensateCreation(Long creatorAccountId, String roomName) {
        // Creation may still be unwinding its transaction. Only the scheduler executes orphan compensation.
        compensation.executeWithoutResult(status -> insert(null, creatorAccountId, "DELETE_ORPHAN_ROOM", roomName, null, false));
    }
    private void insert(Long meetingId, Long creatorAccountId, String operation, String roomName, String identity, boolean attemptNow) {
        String key = MeetingChatTokenUtils.sha256(operation + "|" + roomName + "|" + (identity == null ? "" : identity));
        var now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("INSERT INTO meeting_media_cleanup_jobs(operation_key,meeting_id,creator_account_id,operation,room_name,target_identity,attempts,next_attempt_at,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,?,0,?,?,?) ON CONFLICT (operation_key) DO NOTHING",
            key, meetingId, creatorAccountId, operation, roomName, identity, now, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM meeting_media_cleanup_jobs WHERE operation_key = ?", Long.class, key);
        if (!attemptNow) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { attempt(id); }
            });
        } else attempt(id);
    }
    private void attempt(Long id) {
        try { worker.process(id); }
        catch (RuntimeException unavailable) {
            // The committed job is authoritative even if the worker cannot open its retry transaction.
            LOG.warn("Committed media cleanup awaits recovery: jobId={}", id);
        }
    }
    public boolean pending(Long meetingId) { return jobs.existsByMeetingId(meetingId); }
}
