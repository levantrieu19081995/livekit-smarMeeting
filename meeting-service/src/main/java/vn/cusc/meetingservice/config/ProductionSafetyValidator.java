package vn.cusc.meetingservice.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyValidator implements SmartInitializingSingleton {
    private final Environment environment;
    private final AiServiceProperties ai;
    private final LiveKitProperties liveKit;
    public ProductionSafetyValidator(Environment environment, AiServiceProperties ai, LiveKitProperties liveKit) {
        this.environment = environment; this.ai = ai; this.liveKit = liveKit;
    }
    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) return;
        requireSecret(ai.getInternalApiKey(), "AI_INTERNAL_API_KEY", 24);
        requireSecret(liveKit.getApiSecret(), "LIVEKIT_API_SECRET", 16);
        if (environment.getProperty("api-safety.rate-limit-enabled", Boolean.class, true) == false)
            throw new IllegalStateException("Production requires API admission limits");
    }
    private void requireSecret(String value, String name, int minimumLength) {
        if (value == null || value.isBlank() || value.length() < minimumLength || value.equals("dev-ai-key")
            || value.equals("secret") || value.startsWith("test-"))
            throw new IllegalStateException("Production requires a non-default " + name);
    }
}
