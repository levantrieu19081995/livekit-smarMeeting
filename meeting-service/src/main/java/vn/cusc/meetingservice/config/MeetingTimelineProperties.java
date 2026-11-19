package vn.cusc.meetingservice.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "meeting.timeline")
public class MeetingTimelineProperties {

    private ZoneId zoneId = ZoneId.of("Asia/Ho_Chi_Minh");

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        if (zoneId == null) {
            throw new IllegalArgumentException("meeting.timeline.zone-id must not be null");
        }
        this.zoneId = zoneId;
    }
}
