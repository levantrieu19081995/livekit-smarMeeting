package vn.cusc.meetingservice.client.conclusion;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;

@Component
public class ConclusionClientImpl implements ConclusionClient {
    private final RestClient client;
    private final AiServiceProperties properties;
    public ConclusionClientImpl(@Qualifier("conclusionRestClient") RestClient client, AiServiceProperties properties) {
        this.client = client; this.properties = properties;
    }
    public ProviderResponse generate(ProviderRequest request) {
        try {
            return client.post().uri("/api/ai/conclusions/generate")
                .header("X-Internal-Api-Key", properties.getInternalApiKey() == null ? "" : properties.getInternalApiKey())
                .body(request).retrieve().body(ProviderResponse.class);
        } catch (HttpClientErrorException error) {
            throw new Failure(error.getStatusCode().value() == 409 || error.getStatusCode().value() == 429,
                error.getStatusCode().value() == 401 ? "CONCLUSION_PROVIDER_AUTH_FAILED" : "CONCLUSION_PROVIDER_BUSY");
        } catch (HttpServerErrorException error) {
            throw new Failure(false, "CONCLUSION_PROVIDER_FAILED");
        } catch (Exception error) {
            throw new Failure(true, "CONCLUSION_CONNECTION_FAILED");
        }
    }
}
