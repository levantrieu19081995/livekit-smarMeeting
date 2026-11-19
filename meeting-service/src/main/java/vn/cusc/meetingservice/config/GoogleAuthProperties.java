package vn.cusc.meetingservice.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "google")
public class GoogleAuthProperties {
    private boolean enabled;
    private String clientId;
    private String allowedDomains;
    private boolean autoProvision;

    public List<String> getAllowedDomainList() {
        if (allowedDomains == null || allowedDomains.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedDomains.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }
}
