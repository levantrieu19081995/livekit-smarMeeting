package vn.cusc.meetingservice.service;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

public interface FileStorageService {
    StoredFile storeMeetingFile(Long meetingId, MultipartFile file) throws IOException;
    void delete(Path path) throws IOException;
    Resource load(Path path) throws IOException;

    record StoredFile(String originalFileName, String storedFileName, String contentType, long size, String storagePath) {
    }
}
