package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.model.enums.MeetingSpeakingStatus;
import vn.cusc.meetingservice.repository.MeetingSpeakingRequestRepository;

/** Scheduled end cleanup survives restart; each meeting follows the same write lock as speaking mutations. */
@Service @RequiredArgsConstructor
public class MeetingSpeakingExpiryService {
    private static final Logger log = LoggerFactory.getLogger(MeetingSpeakingExpiryService.class);
    private final MeetingSpeakingRequestRepository requests;
    private final MeetingSpeakingService speaking;

    @Scheduled(fixedDelayString = "${app.speaking.cleanup-delay-ms:30000}", initialDelayString = "${app.speaking.cleanup-delay-ms:30000}")
    public void finalizeExpiredMeetings() {
        var ids = requests.findTerminalMeetingIds(List.of(MeetingSpeakingStatus.WAITING, MeetingSpeakingStatus.SPEAKING),
            LocalDateTime.now(), PageRequest.of(0, 100));
        for (Long id : ids) {
            try { speaking.finalizeIfTerminal(id); }
            catch (RuntimeException error) { log.warn("Speaking cleanup will retry meetingId={}", id, error); }
        }
    }
}
