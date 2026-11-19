package vn.cusc.meetingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Session session = new Session();
    private Registration registration = new Registration();

    @Getter
    @Setter
    public static class Session {
        private String timeout = "8h";
    }

    @Getter
    @Setter
    public static class Registration {
        private boolean enabled = true;
        private boolean localAutoActivate = false;
    }
}
