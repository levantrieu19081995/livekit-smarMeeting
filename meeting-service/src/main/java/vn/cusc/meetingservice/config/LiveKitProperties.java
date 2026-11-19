package vn.cusc.meetingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "livekit")
@org.springframework.validation.annotation.Validated
public class LiveKitProperties {
    private String url;
    private String apiKey;
    private String apiSecret;
    private int emptyTimeout;
    private int departureTimeout;
    @jakarta.validation.constraints.Min(30) @jakarta.validation.constraints.Max(600)
    private long joinTokenTtlSeconds = 300;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(30)
    private long requestTimeoutSeconds = 10;
}
