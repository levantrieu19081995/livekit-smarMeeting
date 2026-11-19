package vn.cusc.meetingservice.initializer;

import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingRepository;

@Configuration
public class DevDataInitializer {

    @Bean
    @Profile("dev")
    public CommandLineRunner seedDevData(MeetingRepository meetingRepository) {
        return args -> {
            if (meetingRepository.count() > 0) {
                return;
            }
            MeetingEntity meeting = new MeetingEntity();
            meeting.setMeetingCode("MTG-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-0001");
            meeting.setTitle("Hop trien khai he thong KTX");
            meeting.setDescription("Trao doi ke hoach trien khai");
            meeting.setRoomName("meeting-dev-seed");
            meeting.setStartTime(LocalDateTime.now().plusDays(1));
            meeting.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
            meeting.setAccessType(MeetingAccessType.PRIVATE);
            meeting.setStatus(MeetingStatus.UPCOMING);

            MeetingParticipantEntity chairman = new MeetingParticipantEntity();
            chairman.setMeeting(meeting);
            chairman.setUserId("U001");
            chairman.setFullName("Nguyen Van A");
            chairman.setEmail("a@cusc.vn");
            chairman.setRole(MeetingRole.CHAIRMAN);
            chairman.setStatus(ParticipantStatus.INVITED);

            MeetingParticipantEntity secretary = new MeetingParticipantEntity();
            secretary.setMeeting(meeting);
            secretary.setUserId("U002");
            secretary.setFullName("Tran Van B");
            secretary.setEmail("b@cusc.vn");
            secretary.setRole(MeetingRole.SECRETARY);
            secretary.setStatus(ParticipantStatus.INVITED);

            meeting.getParticipants().add(chairman);
            meeting.getParticipants().add(secretary);
            meetingRepository.save(meeting);
        };
    }
}
