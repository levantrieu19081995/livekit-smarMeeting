package vn.cusc.meetingservice.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class MeetingCodeGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final AtomicInteger sequence = new AtomicInteger(1);

    public String generate() {
        int nextValue = sequence.getAndIncrement();
        return "MTG-" + LocalDate.now().format(FORMATTER) + "-" + String.format("%04d", nextValue);
    }
}
