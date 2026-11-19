package vn.cusc.meetingservice.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.repository.MeetingPollRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;

/** Participates in the authorized terminal Meeting transaction; never commits independently. */
@Service
@RequiredArgsConstructor
public class MeetingPollTerminalService {
    private final MeetingRepository meetings;
    private final MeetingPollRepository polls;

    @Transactional(propagation = Propagation.MANDATORY)
    public void closeForTerminalTransition(Long meetingId) {
        // Keep the same aggregate lock order as lifecycle/vote, including future callers.
        meetings.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
        polls.findOpenByMeetingId(meetingId).ifPresent(open -> {
            var poll = polls.findByIdAndMeetingIdForUpdate(open.getId(), meetingId).orElseThrow();
            if (poll.getStatus() == MeetingPollStatus.OPEN) {
                poll.setStatus(MeetingPollStatus.CLOSED);
                poll.setClosedAt(LocalDateTime.now());
                polls.saveAndFlush(poll);
            }
        });
    }
}
