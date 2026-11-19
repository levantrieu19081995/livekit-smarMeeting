package vn.cusc.meetingservice.service.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.cusc.meetingservice.dto.response.MeetingDocumentResponse;
import vn.cusc.meetingservice.dto.response.MeetingDocumentFileResponse;
import vn.cusc.meetingservice.exception.DocumentNotFoundException;
import vn.cusc.meetingservice.mapper.MeetingDocumentMapper;
import vn.cusc.meetingservice.repository.MeetingDocumentRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.FileStorageService;
import vn.cusc.meetingservice.service.MeetingDocumentService;
import vn.cusc.meetingservice.service.MeetingDocumentWriteService;
import vn.cusc.meetingservice.service.MeetingFileCleanupService;
import vn.cusc.meetingservice.service.MeetingMinutesAuthorizationService;
import vn.cusc.meetingservice.util.DocumentContentPolicy;

@Service
public class MeetingDocumentServiceImpl implements MeetingDocumentService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MeetingDocumentServiceImpl.class);
    private final MeetingDocumentRepository documents;
    private final FileStorageService storage;
    private final MeetingDocumentMapper mapper;
    private final MeetingMinutesAuthorizationService authorization;
    private final MeetingDocumentWriteService writes;

    @Autowired
    public MeetingDocumentServiceImpl(MeetingRepository meetings, MeetingDocumentRepository documents,
        FileStorageService storage, MeetingDocumentMapper mapper, MeetingMinutesAuthorizationService authorization,
        MeetingDocumentWriteService writes) {
        this.documents = documents; this.storage = storage; this.mapper = mapper;
        this.authorization = authorization; this.writes = writes;
    }
    public MeetingDocumentServiceImpl(MeetingRepository meetings, MeetingDocumentRepository documents,
        FileStorageService storage, MeetingDocumentMapper mapper, MeetingMinutesAuthorizationService authorization) {
        this(meetings, documents, storage, mapper, authorization,
            new MeetingDocumentWriteService(meetings, documents, authorization, mapper, null, storage));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeetingDocumentResponse> getDocuments(Long meetingId) {
        authorization.requireMeetingView(meetingId);
        return documents.findByMeetingId(meetingId).stream().map(mapper::toResponse).toList();
    }

    @Override
    public MeetingDocumentResponse uploadDocument(Long meetingId, MultipartFile file) {
        writes.requireUploadAccess(meetingId);
        FileStorageService.StoredFile stored = null;
        try {
            stored = storage.storeMeetingFile(meetingId, file);
            String hash;
            try (var input = new DigestInputStream(storage.load(Path.of(stored.storagePath())).getInputStream(),
                MessageDigest.getInstance("SHA-256"))) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) != -1) { }
                hash = HexFormat.of().formatHex(input.getMessageDigest().digest());
            }
            return writes.persistUpload(meetingId, stored, hash);
        } catch (IOException | NoSuchAlgorithmException exception) {
            if (stored != null) {
                try { storage.delete(Path.of(stored.storagePath())); }
                catch (IOException cleanup) { LOGGER.warn("Unpublished upload requires orphan reconciliation"); }
            }
            throw new IllegalStateException("Không thể lưu tài liệu. Vui lòng thử lại.", exception);
        }
        // A DB transaction rollback owns cleanup. An uncertain commit is reconciled against retained DB references.
    }

    @Override
    public void deleteDocument(Long meetingId, Long documentId) { writes.delete(meetingId, documentId); }

    @Override
    @Transactional(readOnly = true)
    public MeetingDocumentFileResponse getDocumentFile(Long meetingId, Long documentId) {
        authorization.requireMeetingView(meetingId);
        var document = documents.findByIdAndMeetingId(documentId, meetingId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        try {
            var resource = storage.load(Path.of(document.getStoragePath()));
            DocumentContentPolicy.Inspection inspection;
            try {
                inspection = resource.isFile() ? DocumentContentPolicy.inspect(resource.getFile().toPath(), document.getOriginalFileName())
                    : new DocumentContentPolicy.Inspection("application/octet-stream", false);
            } catch (vn.cusc.meetingservice.exception.BusinessValidationException unsupportedLegacy) {
                inspection = new DocumentContentPolicy.Inspection("application/octet-stream", false);
            }
            return new MeetingDocumentFileResponse(resource, document.getOriginalFileName(), inspection.contentType(),
                resource.contentLength(), inspection.inlineSafe());
        } catch (IOException exception) { throw new DocumentNotFoundException(documentId); }
    }
}
