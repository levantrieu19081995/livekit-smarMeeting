package vn.cusc.meetingservice.client.capture;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.dto.capture.StartCaptureRequest;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;
import vn.cusc.meetingservice.exception.AiServiceException;

@Component
public class CaptureClientImpl implements CaptureClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public CaptureClientImpl(RestClient aiRestClient, AiServiceProperties properties) {
        this.restClient = aiRestClient;
        this.properties = properties;
    }

    @Override
    public CaptureServiceSessionResponse start(StartCaptureRequest request) {
        try {
            return restClient.post()
                .uri("/api/capture/meetings/{meetingId}/start", request.meetingId())
                .header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
                .body(request)
                .retrieve()
                .body(CaptureServiceSessionResponse.class);
        } catch (Exception exception) {
            throw new AiServiceException("Khong the bat dau ghi am", exception);
        }
    }

    @Override
    public CaptureServiceSessionResponse stop(Long meetingId, StopCaptureRequest request) {
        try {
            return restClient.post()
                .uri("/api/capture/meetings/{meetingId}/stop", meetingId)
                .header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
                .body(request)
                .retrieve()
                .body(CaptureServiceSessionResponse.class);
        } catch (Exception exception) {
            throw new AiServiceException("Khong the ket thuc ghi am", exception);
        }
    }

    @Override
    public CaptureServiceStatusResponse status(Long meetingId, Long captureSessionId, String externalSessionId) {
        try {
            return restClient.get()
                .uri(builder -> {
                    builder.path("/api/capture/meetings/{meetingId}/status")
                        .queryParam("captureSessionId", captureSessionId);
                    if (externalSessionId != null && !externalSessionId.isBlank()) {
                        builder.queryParam("externalSessionId", externalSessionId);
                    }
                    return builder.build(meetingId);
                })
                .header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
                .retrieve()
                .body(CaptureServiceStatusResponse.class);
        } catch (Exception exception) {
            throw new AiServiceException("Khong the lay trang thai ghi am", exception);
        }
    }
}
