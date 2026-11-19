package vn.cusc.meetingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai-service")
public class AiServiceProperties {
    private String baseUrl;
    private String internalApiKey;
    private int connectTimeoutSeconds;
    private int readTimeoutSeconds;
}
