package vn.cusc.meetingservice.dto.response;

public record LiveKitRoomResponse(
    String roomName,
    String roomSid,
    int numParticipants,
    long creationTime
) {
}
