package vn.cusc.meetingservice.client.stt;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.stt.request.StartSttJobRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobAcceptedResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobStatusResponse;
import vn.cusc.meetingservice.exception.AiServiceException;

@Component
public class SttClientImpl implements SttClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public SttClientImpl(RestClient aiRestClient, AiServiceProperties properties) {
        this.restClient = aiRestClient;
        this.properties = properties;
    }

    @Override
    public SttJobAcceptedResponse startJob(
        StartSttJobRequest request
    ) {
        try {
            RequestBodySpec spec = restClient.post()
                .uri("/api/postprocessing/stt/jobs");
            if (StringUtils.hasText(properties.getInternalApiKey())) {
                spec.header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey());
            }
            return spec
                .body(request)
                .retrieve()
                .body(SttJobAcceptedResponse.class);
        } catch (Exception exception) {
            throw new AiServiceException("Khong the khoi tao STT job", exception);
        }
    }

    @Override
    public SttJobResultResponse getResult(
        String processingId
    ) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri("/api/postprocessing/stt/jobs/{processingId}/result", processingId);
            if (StringUtils.hasText(properties.getInternalApiKey())) {
                spec.header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey());
            }
            return spec
                .retrieve()
                .body(SttJobResultResponse.class);
        } catch (Exception exception) {
            throw new AiServiceException("Khong the lay ket qua STT", exception);
        }
    }

    @Override
    public Optional<SttJobStatusResponse> getStatus(
        String processingId
    ) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri("/api/postprocessing/stt/jobs/{processingId}", processingId);
            if (StringUtils.hasText(properties.getInternalApiKey())) {
                spec.header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey());
            }
            SttJobStatusResponse response = spec
                .retrieve()
                .body(SttJobStatusResponse.class);
            if (response == null) {
                throw new AiServiceException("Python STT status response is empty");
            }
            return Optional.of(response);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiServiceException("Khong the kiem tra STT job", exception);
        }
    }
}
