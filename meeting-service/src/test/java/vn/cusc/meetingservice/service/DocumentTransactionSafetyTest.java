package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.cusc.meetingservice.config.StorageProperties;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.mapper.MeetingDocumentMapper;
import vn.cusc.meetingservice.model.enums.*;
import vn.cusc.meetingservice.repository.*;
import vn.cusc.meetingservice.service.impl.*;
import vn.cusc.meetingservice.util.FileNameUtils;

@DataJpaTest(properties = {"spring.jpa.show-sql=false", "spring.datasource.url=jdbc:h2:mem:document-safety;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(MeetingFileCleanupService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentTransactionSafetyTest {
    @Autowired MeetingRepository meetings;
    @Autowired UserAccountRepository accounts;
    @Autowired MeetingDocumentRepository documents;
    @Autowired MeetingFileCleanupJobRepository jobs;
    @Autowired MeetingFileCleanupService cleanup;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path directory;
    MeetingDocumentServiceImpl service;
    LocalFileStorageServiceImpl storage;
    MeetingEntity meeting;
    TransactionTemplate tx;
    StorageProperties properties;
    vn.cusc.meetingservice.entity.UserAccountEntity account;

    @BeforeEach void setUp() {
        jobs.deleteAll(); documents.deleteAll(); meetings.deleteAll();
        account = new vn.cusc.meetingservice.entity.UserAccountEntity(); account.setPublicId(UUID.randomUUID().toString());
        account.setUsername("document-" + UUID.randomUUID()); account.setNormalizedEmail(UUID.randomUUID() + "@example.test");
        account.setFullName("Document Actor"); account.setAccountStatus(AccountStatus.ACTIVE); account.setSystemRole(SystemRole.USER);
        account = accounts.saveAndFlush(account);
        meeting = new MeetingEntity(); meeting.setTitle("Document test"); meeting.setMeetingCode(UUID.randomUUID().toString().replace("-", ""));
        meeting.setRoomName("room-" + UUID.randomUUID()); meeting.setAccessType(MeetingAccessType.PUBLIC);
        meeting.setCreationMode(MeetingCreationMode.SCHEDULED); meeting.setStatus(MeetingStatus.UPCOMING);
        meeting.setStartTime(LocalDateTime.now().plusHours(1)); meeting.setEndTime(LocalDateTime.now().plusHours(2)); meeting = meetings.saveAndFlush(meeting);
        properties = new StorageProperties(); properties.setUploadDir(directory.toString()); storage = new LocalFileStorageServiceImpl(properties, new FileNameUtils());
        var authorization = mock(MeetingMinutesAuthorizationService.class);
        when(authorization.requireMeetingAccess(meeting.getId())).thenReturn(meeting);
        when(authorization.requirePrincipal()).thenReturn(new vn.cusc.meetingservice.security.MeetingPrincipal(account.getId(), account.getPublicId(),
            account.getUsername(), account.getNormalizedEmail(), account.getFullName(), null, AccountStatus.ACTIVE, SystemRole.USER, false, AuthProvider.LOCAL, null, account.getSecurityVersion()));
        when(authorization.requireCurrentAccount()).thenAnswer(call -> accounts.findById(account.getId()).orElseThrow());
        var mapper = new MeetingDocumentMapper();
        var writes = new MeetingDocumentWriteService(meetings, documents, authorization, mapper, cleanup, storage);
        service = new MeetingDocumentServiceImpl(meetings, documents, storage, mapper, authorization, writes);
        tx = new TransactionTemplate(transactions);
    }
    @Test void uploadRollbackRemovesTheNewFileAndMetadataTogether() throws Exception {
        var path = new java.util.concurrent.atomic.AtomicReference<Path>();
        tx.executeWithoutResult(status -> {
            var result = service.uploadDocument(meeting.getId(), file()); path.set(path(result.storedFileName()));
            assertTrue(Files.exists(path.get())); status.setRollbackOnly();
        });
        assertEquals(0, documents.count()); assertFalse(Files.exists(path.get()));
    }
    @Test void deleteRollbackRetainsBothTheReferenceAndPhysicalFile() throws Exception {
        var result = tx.execute(status -> service.uploadDocument(meeting.getId(), file()));
        tx.executeWithoutResult(status -> { service.deleteDocument(meeting.getId(), result.id()); status.setRollbackOnly(); });
        assertTrue(documents.findById(result.id()).isPresent()); assertTrue(Files.exists(path(result.storedFileName()))); assertEquals(0, jobs.count());
    }
    @Test void committedDeleteHasDurableCleanupAndRestartsCanFinishIt() throws Exception {
        var result = tx.execute(status -> service.uploadDocument(meeting.getId(), file()));
        tx.executeWithoutResult(status -> service.deleteDocument(meeting.getId(), result.id()));
        assertEquals(0, documents.count()); assertTrue(Files.exists(path(result.storedFileName()))); assertEquals(1, jobs.count());
        new MeetingFileCleanupDispatcher(cleanup, jobs, documents, storage, properties, true, 86400).dispatch();
        assertFalse(Files.exists(path(result.storedFileName()))); assertEquals(0, jobs.count());
    }
    @Test void lostUploadResponseRetryReturnsTheSameAttachmentAndQueuesDuplicateFileCleanup() throws Exception {
        var first = tx.execute(status -> service.uploadDocument(meeting.getId(), file()));
        var retried = tx.execute(status -> service.uploadDocument(meeting.getId(), file()));
        assertEquals(first.id(), retried.id()); assertEquals(1, documents.count()); assertEquals(1, jobs.count());
        new MeetingFileCleanupDispatcher(cleanup, jobs, documents, storage, properties, true, 86400).dispatch();
        try (var files = Files.walk(directory)) { assertEquals(1, files.filter(Files::isRegularFile).count()); }
        assertTrue(Files.exists(path(first.storedFileName())));
    }
    @Test void failedIoRetainsJobBackoffAndAStaleLeaseCannotDeleteNewClaimState() throws Exception {
        Path path = directory.resolve("orphan.txt"); Files.writeString(path, "safe"); cleanup.enqueue(path.toString());
        var job = jobs.findAll().get(0); var old = cleanup.claim(job.getId()).orElseThrow();
        job = jobs.findById(job.getId()).orElseThrow(); job.setLeaseUntil(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)); jobs.saveAndFlush(job);
        var current = cleanup.claim(job.getId()).orElseThrow(); cleanup.completed(old); assertEquals(1, jobs.count());
        cleanup.failed(current); job = jobs.findById(job.getId()).orElseThrow(); assertEquals(1, job.getAttempts());
        assertNull(job.getLeaseToken()); assertTrue(job.getNextAttemptAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC)));
        assertTrue(cleanup.claim(job.getId()).isEmpty()); assertTrue(Files.exists(path));
    }
    @Test void referencedFileIsNeverDeletedByOrphanReconciliationOrAnErroneousIntent() {
        var result = tx.execute(status -> service.uploadDocument(meeting.getId(), file())); cleanup.enqueue(path(result.storedFileName()).toString());
        new MeetingFileCleanupDispatcher(cleanup, jobs, documents, storage, properties, true, 3600).dispatch();
        assertTrue(Files.exists(path(result.storedFileName()))); assertEquals(0, jobs.count());
    }
    @Test void concurrentCleanupEnqueueUsesOneDurableIntentWithoutRollingBackCallers() throws Exception {
        Path path = directory.resolve("same-orphan.txt");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var work = java.util.stream.IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> cleanup.enqueue(path.toString()))).toList();
            for (var future : work) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(1, jobs.count());
    }
    @Test void disableCommittedDuringFileCopyIsRecheckedBeforeMetadataInsertAndRollsBackTheNewFile() throws Exception {
        var separate = new TransactionTemplate(transactions); separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var upload = new MockMultipartFile("file", "note.txt", "text/plain", "meeting notes".getBytes()) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                separate.executeWithoutResult(status -> {
                    var revoked = accounts.findById(account.getId()).orElseThrow(); revoked.setAccountStatus(AccountStatus.DISABLED);
                    revoked.setSecurityVersion(revoked.getSecurityVersion() + 1); accounts.saveAndFlush(revoked);
                });
                return super.getInputStream();
            }
        };
        assertThrows(vn.cusc.meetingservice.exception.MeetingAccessDeniedException.class,
            () -> tx.execute(status -> service.uploadDocument(meeting.getId(), upload)));
        assertEquals(AccountStatus.DISABLED, accounts.findById(account.getId()).orElseThrow().getAccountStatus());
        assertEquals(0, documents.count()); assertEquals(0, jobs.count());
        try (var files = Files.walk(directory)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }
    @Test void refusedDeleteWithRevokedCredentialPreservesMetadataAndFile() {
        var result = tx.execute(status -> service.uploadDocument(meeting.getId(), file()));
        var revoked = accounts.findById(account.getId()).orElseThrow(); revoked.setSecurityVersion(1); accounts.saveAndFlush(revoked);
        assertThrows(vn.cusc.meetingservice.exception.MeetingAccessDeniedException.class,
            () -> tx.execute(status -> { service.deleteDocument(meeting.getId(), result.id()); return null; }));
        assertTrue(documents.findById(result.id()).isPresent()); assertTrue(Files.exists(path(result.storedFileName()))); assertEquals(0, jobs.count());
    }
    private MockMultipartFile file() { return new MockMultipartFile("file", "note.txt", "text/plain", "meeting notes".getBytes()); }
    private Path path(String storedName) { return directory.resolve("meetings").resolve(meeting.getId().toString()).resolve(storedName); }
}
