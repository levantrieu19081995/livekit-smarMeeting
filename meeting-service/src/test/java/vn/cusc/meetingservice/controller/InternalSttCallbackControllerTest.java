package vn.cusc.meetingservice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.stt.callback.SttCompletionCallbackRequest;
import vn.cusc.meetingservice.dto.stt.callback.SttProgressCallbackRequest;
import vn.cusc.meetingservice.model.enums.SttTerminalStatus;
import vn.cusc.meetingservice.service.MeetingMinutesSttJobStateService;

class InternalSttCallbackControllerTest {

    @Test
    void progressCallbackRejectsWhenConfiguredInternalKeyIsBlank() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttProgressCallbackController controller = new InternalSttProgressCallbackController(
            propertiesWithKey(" "),
            stateService
        );

        var response = controller.applyProgress("processing-1", "any-incoming-key", progressRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(stateService);
    }

    @Test
    void completionCallbackRejectsWhenConfiguredInternalKeyIsBlank() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttCompletionCallbackController controller = new InternalSttCompletionCallbackController(
            propertiesWithKey(" "),
            stateService
        );

        var response = controller.applyCompletion("processing-1", "any-incoming-key", completionRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(stateService);
    }

    @Test
    void progressCallbackAllowsExactConfiguredInternalKey() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttProgressCallbackController controller = new InternalSttProgressCallbackController(
            propertiesWithKey("expected-key"),
            stateService
        );

        var response = controller.applyProgress("processing-1", "expected-key", progressRequest());

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(stateService).applySttProgress(eq("processing-1"), any(SttProgressCallbackRequest.class));
    }

    @Test
    void completionCallbackAllowsExactConfiguredInternalKey() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttCompletionCallbackController controller = new InternalSttCompletionCallbackController(
            propertiesWithKey("expected-key"),
            stateService
        );

        var response = controller.applyCompletion("processing-1", "expected-key", completionRequest());

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(stateService).applySttCompletion(eq("processing-1"), any(SttCompletionCallbackRequest.class));
    }

    @Test
    void progressCallbackRejectsMissingIncomingInternalKey() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttProgressCallbackController controller = new InternalSttProgressCallbackController(
            propertiesWithKey("expected-key"),
            stateService
        );

        var response = controller.applyProgress("processing-1", null, progressRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(stateService, never()).applySttProgress(any(), any());
    }

    @Test
    void completionCallbackRejectsMissingIncomingInternalKey() {
        MeetingMinutesSttJobStateService stateService = mock(MeetingMinutesSttJobStateService.class);
        InternalSttCompletionCallbackController controller = new InternalSttCompletionCallbackController(
            propertiesWithKey("expected-key"),
            stateService
        );

        var response = controller.applyCompletion("processing-1", null, completionRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(stateService, never()).applySttCompletion(any(), any());
    }

    private AiServiceProperties propertiesWithKey(String internalApiKey) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setInternalApiKey(internalApiKey);
        return properties;
    }

    private SttProgressCallbackRequest progressRequest() {
        return new SttProgressCallbackRequest(12L, 1, 3, 33.33);
    }

    private SttCompletionCallbackRequest completionRequest() {
        return new SttCompletionCallbackRequest(
            12L,
            SttTerminalStatus.COMPLETED,
            2,
            3,
            3,
            3,
            3,
            0,
            null
        );
    }
}
