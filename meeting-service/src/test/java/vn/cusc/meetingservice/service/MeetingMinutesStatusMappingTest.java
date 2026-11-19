package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.service.impl.MeetingMinutesQueryServiceImpl;

class MeetingMinutesStatusMappingTest {

    @Test
    void mapsAllDerivedMinutesStatusesWithUsableMinutesTakingPriority() {
        assertEquals(MinutesStatus.NOT_GENERATED, MeetingMinutesQueryServiceImpl.resolveStatus(null, null));
        assertEquals(
            MinutesStatus.PROCESSING,
            MeetingMinutesQueryServiceImpl.resolveStatus(null, job(MinutesJobStatus.QUEUED))
        );
        assertEquals(
            MinutesStatus.FAILED,
            MeetingMinutesQueryServiceImpl.resolveStatus(null, job(MinutesJobStatus.FAILED))
        );

        MeetingMinutesEntity draft = new MeetingMinutesEntity();
        draft.setStatus(MinutesStatus.DRAFT);
        assertEquals(
            MinutesStatus.DRAFT,
            MeetingMinutesQueryServiceImpl.resolveStatus(draft, job(MinutesJobStatus.FAILED))
        );
        draft.setStatus(MinutesStatus.FINAL);
        assertEquals(
            MinutesStatus.FINAL,
            MeetingMinutesQueryServiceImpl.resolveStatus(draft, job(MinutesJobStatus.PROCESSING))
        );
    }

    private MeetingMinutesJobEntity job(MinutesJobStatus status) {
        MeetingMinutesJobEntity job = new MeetingMinutesJobEntity();
        job.setStatus(status);
        return job;
    }
}
