package vn.cusc.meetingservice.util;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FileNameUtils {

    public String buildStoredFileName(String originalFileName) {
        String sanitized = Path.of(originalFileName).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        return UUID.randomUUID() + "-" + sanitized;
    }
}
