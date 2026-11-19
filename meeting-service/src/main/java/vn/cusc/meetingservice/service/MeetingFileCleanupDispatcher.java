package vn.cusc.meetingservice.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.config.StorageProperties;
import vn.cusc.meetingservice.repository.MeetingDocumentRepository;
import vn.cusc.meetingservice.repository.MeetingFileCleanupJobRepository;

@Component
public class MeetingFileCleanupDispatcher {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MeetingFileCleanupDispatcher.class);
    private final MeetingFileCleanupService state;
    private final MeetingFileCleanupJobRepository jobs;
    private final MeetingDocumentRepository documents;
    private final FileStorageService storage;
    private final StorageProperties properties;
    private final boolean enabled;
    private final long orphanGraceSeconds;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private long sweepOffset;
    public MeetingFileCleanupDispatcher(MeetingFileCleanupService state, MeetingFileCleanupJobRepository jobs,
        MeetingDocumentRepository documents, FileStorageService storage, StorageProperties properties,
        @Value("${storage.cleanup-enabled:true}") boolean enabled,
        @Value("${storage.orphan-grace-seconds:86400}") long orphanGraceSeconds) {
        this.state = state; this.jobs = jobs; this.documents = documents; this.storage = storage;
        this.properties = properties; this.enabled = enabled; this.orphanGraceSeconds = Math.max(3600, orphanGraceSeconds);
    }
    @Scheduled(fixedDelayString = "${storage.cleanup-interval-ms:10000}", initialDelayString = "${storage.cleanup-initial-delay-ms:15000}")
    public void dispatch() {
        if (!enabled) return;
        for (Long id : jobs.findReadyIds(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), PageRequest.of(0, 10))) {
            var optional = state.claim(id); if (optional.isEmpty()) continue;
            var claim = optional.get();
            try { storage.delete(Path.of(claim.path())); state.completed(claim); }
            catch (IOException | RuntimeException exception) { state.failed(claim); LOGGER.warn("File cleanup job {} will retry", claim.id()); }
        }
    }
    @Scheduled(fixedDelayString = "${storage.orphan-sweep-interval-ms:60000}", initialDelayString = "${storage.cleanup-initial-delay-ms:15000}")
    public void sweepOrphans() {
        if (!enabled || !sweeping.compareAndSet(false, true)) return;
        try {
            Path directory = Path.of(properties.getUploadDir(), "meetings").toAbsolutePath().normalize();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
            try (var walk = Files.walk(directory, 2)) {
                var batch = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .skip(sweepOffset).limit(1000).toList();
                sweepOffset = batch.size() < 1000 ? 0 : sweepOffset + batch.size();
                Instant cutoff = Instant.now().minusSeconds(orphanGraceSeconds);
                for (Path path : batch) {
                    if (!Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                    if (!documents.existsByStoredFileName(path.getFileName().toString())) state.enqueue(path.toString());
                }
            }
        } catch (IOException | RuntimeException exception) { LOGGER.warn("Document orphan reconciliation will retry"); }
        finally { sweeping.set(false); }
    }
}
