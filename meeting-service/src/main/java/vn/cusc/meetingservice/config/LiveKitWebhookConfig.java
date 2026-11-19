package vn.cusc.meetingservice.config;

import io.livekit.server.WebhookReceiver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LiveKitWebhookConfig {

    @Bean
    public WebhookReceiver webhookReceiver(LiveKitProperties liveKitProperties) {
        return new WebhookReceiver(liveKitProperties.getApiKey(), liveKitProperties.getApiSecret());
    }
}
