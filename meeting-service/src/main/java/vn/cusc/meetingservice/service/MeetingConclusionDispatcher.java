package vn.cusc.meetingservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.client.conclusion.ConclusionClient;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.ProviderRequest;

@Component @RequiredArgsConstructor
public class MeetingConclusionDispatcher {
    private final MeetingConclusionService jobs;
    private final ConclusionClient client;
    @Scheduled(fixedDelayString = "${conclusion.dispatch-delay-ms:2000}", scheduler = "conclusionScheduler")
    public void dispatch() {
        for (Long meetingId : jobs.due()) process(meetingId);
    }
    public void process(Long meetingId) {
        jobs.claim(meetingId).ifPresent(work -> {
            try {
                var response = client.generate(new ProviderRequest(work.generationId(), work.meetingId(), work.context()));
                jobs.complete(work, response);
            } catch (ConclusionClient.Failure error) { jobs.fail(work, error.code, error.retryable); }
            catch (Exception error) { jobs.fail(work, "CONCLUSION_INVALID_RESULT", false); }
        });
    }
}
