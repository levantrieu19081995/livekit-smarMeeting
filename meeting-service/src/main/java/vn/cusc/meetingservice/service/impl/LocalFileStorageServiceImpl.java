package vn.cusc.meetingservice.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import vn.cusc.meetingservice.config.StorageProperties;
import vn.cusc.meetingservice.service.FileStorageService;
import vn.cusc.meetingservice.util.FileNameUtils;
import vn.cusc.meetingservice.util.DocumentContentPolicy;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.util.UUID;

@Service
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final StorageProperties storageProperties;
    private final FileNameUtils fileNameUtils;

    public LocalFileStorageServiceImpl(StorageProperties storageProperties, FileNameUtils fileNameUtils) {
        this.storageProperties = storageProperties;
        this.fileNameUtils = fileNameUtils;
    }

    @Override
    public StoredFile storeMeetingFile(Long meetingId, MultipartFile file) throws IOException {
        if (meetingId == null || meetingId < 1 || file == null || file.isEmpty())
            throw new vn.cusc.meetingservice.exception.BusinessValidationException("Tài liệu cần có nội dung.");
        String original = DocumentContentPolicy.fileName(file.getOriginalFilename());
        String suffix = original.substring(original.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        String storedFileName = UUID.randomUUID() + "." + suffix;
        Path directory = Paths.get(storageProperties.getUploadDir(), "meetings", String.valueOf(meetingId)).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        validateStoredPath(directory);
        Path target = directory.resolve(storedFileName);
        Path staging = directory.resolve(".uploading-" + UUID.randomUUID() + ".part");
        try {
            long bytes = 0;
            try (InputStream inputStream = file.getInputStream(); var output = Files.newOutputStream(staging, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > DocumentContentPolicy.MAX_BYTES)
                        throw new vn.cusc.meetingservice.exception.BusinessValidationException("Tài liệu tối đa 20 MB.");
                    output.write(buffer, 0, count);
                }
            }
            var inspection = DocumentContentPolicy.inspect(staging, original);
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(staging, target); }
            return new StoredFile(original, storedFileName, inspection.contentType(), bytes, target.toString());
        } catch (IOException | RuntimeException exception) {
            try { Files.deleteIfExists(staging); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(validateStoredPath(path));
    }

    @Override
    public Resource load(Path path) throws IOException {
        Path validated = validateStoredPath(path);
        if (!Files.isRegularFile(validated, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Stored file does not exist");
        }
        return new FileSystemResource(validated);
    }

    private Path validateStoredPath(Path path) throws IOException {
        Path storageRoot = Paths.get(storageProperties.getUploadDir()).toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(storageRoot)) {
            throw new IOException("Stored file is outside configured storage root");
        }
        if (Files.exists(storageRoot)) {
            Path realRoot = storageRoot.toRealPath();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (existing == null || !existing.toRealPath().startsWith(realRoot) || Files.isSymbolicLink(candidate))
                throw new IOException("Stored file is outside configured storage root");
        }
        return candidate;
    }
}
