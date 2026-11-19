package vn.cusc.meetingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "api-safety")
public class ApiSafetyProperties {
    private boolean rateLimitEnabled = true;
    private int maximumRateKeys = 20000;
    private int loginPerIdentityPerMinute = 30;
    private int loginPerAddressPerMinute = 600;
    private int joinPerAddressPerMinute = 600;
    private int joinPerActorPerMinute = 60;
    private int writesPerActorPerMinute = 180;
    private int chatPerAddressPerMinute = 6000;
}
