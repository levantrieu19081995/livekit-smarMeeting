package vn.cusc.meetingservice.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import vn.cusc.meetingservice.security.InternalApiKeyValidator;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService;

@Hidden
@RestController
@RequestMapping("/internal/api/minutes/stt/jobs")
public class InternalSttCompletionCallbackController {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final AiServiceProperties aiServiceProperties;
    private final MeetingMinutesSttJobStateService stateService;

    public InternalSttCompletionCallbackController(
        AiServiceProperties aiServiceProperties,
        MeetingMinutesSttJobStateService stateService
    ) {
        this.aiServiceProperties = aiServiceProperties;
        this.stateService = stateService;
    }

    @PostMapping("/{processingId}/completion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> applyCompletion(
        @PathVariable String processingId,

        @RequestHeader(
            value = INTERNAL_API_KEY_HEADER,
            required = false
        )
        String internalApiKey,

        @Valid
        @RequestBody
        SttCompletionCallbackRequest request
    ) {
        if (!hasValidInternalApiKey(internalApiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        stateService.applySttCompletion(processingId, request);
        return ResponseEntity.noContent().build();
    }

    private boolean hasValidInternalApiKey(String internalApiKey) {
        String expectedKey = aiServiceProperties.getInternalApiKey();
        return InternalApiKeyValidator.matches(expectedKey, internalApiKey);
    }
}
