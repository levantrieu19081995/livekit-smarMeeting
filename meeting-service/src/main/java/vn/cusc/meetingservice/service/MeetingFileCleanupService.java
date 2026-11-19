package vn.cusc.meetingservice.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.entity.MeetingFileCleanupJobEntity;
import vn.cusc.meetingservice.repository.MeetingDocumentRepository;
import vn.cusc.meetingservice.repository.MeetingFileCleanupJobRepository;

/** Durable deletion intents are committed with document/meeting removal. Files are deleted outside DB locks. */
@Service
public class MeetingFileCleanupService {
    public record Claim(Long id, String path, String leaseToken) { }
    private final MeetingFileCleanupJobRepository jobs;
    private final MeetingDocumentRepository documents;
    public MeetingFileCleanupService(MeetingFileCleanupJobRepository jobs, MeetingDocumentRepository documents) {
        this.jobs = jobs; this.documents = documents;
    }

    @Transactional
    public void enqueue(String path) {
        String normalized = Path.of(path).toAbsolutePath().normalize().toString();
        jobs.enqueueIfAbsent(normalized, LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Transactional
    public void enqueueForMeetingDeletion(Long meetingId) {
        for (var document : documents.findByMeetingId(meetingId)) enqueue(document.getStoragePath());
    }

    @Transactional
    public Optional<Claim> claim(Long id) {
        var job = jobs.findByIdForUpdate(id).orElse(null);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (job == null || job.getNextAttemptAt().isAfter(now) || job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now)) return Optional.empty();
        if (documents.existsByStoredFileName(Path.of(job.getStoragePath()).getFileName().toString())) {
            jobs.delete(job); return Optional.empty(); // An active reference always wins, even for legacy relative paths.
        }
        job.setLeaseToken(UUID.randomUUID().toString()); job.setLeaseUntil(now.plusMinutes(2));
        jobs.save(job);
        return Optional.of(new Claim(job.getId(), job.getStoragePath(), job.getLeaseToken()));
    }

    @Transactional
    public void completed(Claim claim) {
        jobs.findByIdForUpdate(claim.id()).filter(job -> Objects.equals(job.getLeaseToken(), claim.leaseToken())).ifPresent(jobs::delete);
    }

    @Transactional
    public void failed(Claim claim) {
        jobs.findByIdForUpdate(claim.id()).filter(job -> Objects.equals(job.getLeaseToken(), claim.leaseToken())).ifPresent(job -> {
            job.setAttempts(Math.min(1000000, job.getAttempts() + 1)); job.setLastErrorCode("STORAGE_DELETE_FAILED");
            job.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(job.getAttempts() >= 10 ? 86400 : Math.min(3600, 10L << Math.min(job.getAttempts(), 8))));
            job.setLeaseToken(null); job.setLeaseUntil(null); jobs.save(job);
        });
    }
}
