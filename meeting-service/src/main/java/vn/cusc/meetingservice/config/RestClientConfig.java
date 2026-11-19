package vn.cusc.meetingservice.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient aiRestClient(AiServiceProperties aiServiceProperties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofSeconds(aiServiceProperties.getConnectTimeoutSeconds()))
            .withReadTimeout(Duration.ofSeconds(aiServiceProperties.getReadTimeoutSeconds()));

        return RestClient.builder()
            .baseUrl(aiServiceProperties.getBaseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    }
}
