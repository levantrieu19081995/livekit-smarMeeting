package vn.cusc.meetingservice.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.entity.MeetingDocumentEntity;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.exception.DocumentNotFoundException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.mapper.MeetingDocumentMapper;
import vn.cusc.meetingservice.model.enums.MeetingDocumentSource;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.repository.MeetingDocumentRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;

/** Only DB metadata/permissions are locked; upload copy/sniff/hash happen in the facade outside this transaction. */
@Service
public class MeetingDocumentWriteService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MeetingDocumentWriteService.class);
    private final MeetingRepository meetings;
    private final MeetingDocumentRepository documents;
    private final MeetingMinutesAuthorizationService authorization;
    private final MeetingDocumentMapper mapper;
    private final MeetingFileCleanupService cleanup;
    private final FileStorageService storage;
    public MeetingDocumentWriteService(MeetingRepository meetings, MeetingDocumentRepository documents,
        MeetingMinutesAuthorizationService authorization, MeetingDocumentMapper mapper,
        MeetingFileCleanupService cleanup, FileStorageService storage) {
        this.meetings = meetings; this.documents = documents; this.authorization = authorization;
        this.mapper = mapper; this.cleanup = cleanup; this.storage = storage;
    }
    @Transactional(readOnly = true)
    public void requireUploadAccess(Long meetingId) { authorization.requireMeetingAccess(meetingId); }

    @Transactional(timeout = 30)
    public MeetingDocumentResponse persistUpload(Long meetingId, FileStorageService.StoredFile stored, String hash) {
        rollbackCleanup(stored.storagePath());
        try {
            MeetingEntity meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
            authorization.requireMeetingAccess(meeting);
            var account = requireFreshAccount();
            var existing = documents.findByMeetingIdAndContentSha256AndOriginalFileName(meetingId, hash, stored.originalFileName()).orElse(null);
            if (existing != null) {
                if (cleanup != null) cleanup.enqueue(stored.storagePath());
                else afterCommitDelete(stored.storagePath());
                return mapper.toResponse(existing);
            }
            var entity = new MeetingDocumentEntity(); entity.setMeeting(meeting); entity.setOriginalFileName(stored.originalFileName());
            entity.setStoredFileName(stored.storedFileName()); entity.setContentType(stored.contentType()); entity.setFileSize(stored.size());
            entity.setStoragePath(stored.storagePath()); entity.setContentSha256(hash);
            entity.setUploadedByAccount(account); entity.setSource(source(meeting));
            return mapper.toResponse(documents.saveAndFlush(entity));
        } catch (RuntimeException exception) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) deleteBestEffort(stored.storagePath());
            throw exception;
        }
    }
    @Transactional
    public void delete(Long meetingId, Long documentId) {
        var meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
        authorization.requireMeetingAccess(meeting);
        requireFreshAccount();
        var document = documents.findByIdAndMeetingId(documentId, meetingId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (cleanup != null) cleanup.enqueue(document.getStoragePath());
        documents.delete(document);
        if (cleanup == null) afterCommitDelete(document.getStoragePath());
    }
    private vn.cusc.meetingservice.entity.UserAccountEntity requireFreshAccount() {
        var account = authorization.requireCurrentAccount();
        if (!vn.cusc.meetingservice.security.AccountCredentialVersions.matches(authorization.requirePrincipal(), account))
            throw new vn.cusc.meetingservice.exception.MeetingAccessDeniedException("Phiên đăng nhập đã thay đổi. Vui lòng đăng nhập lại.");
        return account;
    }
    private void rollbackCleanup(String path) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) deleteBestEffort(path); }
        });
    }
    private void afterCommitDelete(String path) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deleteBestEffort(path); }
        });
        else deleteBestEffort(path);
    }
    private void deleteBestEffort(String path) {
        try { storage.delete(Path.of(path)); }
        catch (IOException | RuntimeException exception) { LOGGER.warn("Document file needs orphan reconciliation after failed cleanup"); }
    }
    private MeetingDocumentSource source(MeetingEntity meeting) {
        var now = LocalDateTime.now();
        if (meeting.getStartTime() == null || now.isBefore(meeting.getStartTime())) return MeetingDocumentSource.BEFORE_MEETING;
        if (meeting.getStatus() != MeetingStatus.COMPLETED && meeting.getStatus() != MeetingStatus.CANCELLED
            && (meeting.getEndTime() == null || !now.isAfter(meeting.getEndTime()))) return MeetingDocumentSource.IN_MEETING;
        return MeetingDocumentSource.AFTER_MEETING;
    }
}
