package vn.cusc.meetingservice.config;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "internal-sso")
public class InternalSsoProperties {
    private boolean enabled;
    private String issuerUri;
    private String clientId;
    private String clientSecret;
    private String scopes = "openid,profile,email";

    public List<String> getScopeList() {
        return Arrays.stream(scopes.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }
}
