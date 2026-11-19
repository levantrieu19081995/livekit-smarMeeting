package vn.cusc.meetingservice.dto.template;

import java.util.List;

public record MinutesTemplateModel(
    Long meetingId,
    Integer version,
    String meetingCode,
    String meetingTitle,
    String meetingDate,
    String meetingTime,
    String location,
    List<MinutesTemplateParticipant> participants,
    String chairmanName,
    String secretaryName,
    String mainContent,
    String conclusion,
    boolean draft
) {
    public MinutesTemplateModel {
        participants = List.copyOf(participants);
    }
}
