package vn.cusc.meetingservice.controller;


// Nhận webhook từ LiveKit
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.cusc.meetingservice.service.LiveKitWebhookService;

@Hidden
@RestController
@RequestMapping("/api/livekit/webhook")
public class LiveKitWebhookController {

    private final LiveKitWebhookService liveKitWebhookService;

    public LiveKitWebhookController(LiveKitWebhookService liveKitWebhookService) {
        this.liveKitWebhookService = liveKitWebhookService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receiveWebhook(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody String payload) {
        liveKitWebhookService.handleWebhook(authorization, payload);
    }
}
