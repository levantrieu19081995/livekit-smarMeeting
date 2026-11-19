package vn.cusc.meetingservice.service;

public interface LiveKitWebhookService {
    void handleWebhook(String authorization, String payload);
}
