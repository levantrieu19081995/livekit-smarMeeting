package vn.cusc.meetingservice.util;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RoomNameGenerator {

    public String generate() {
        return "meeting-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
