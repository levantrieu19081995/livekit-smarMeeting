package vn.cusc.meetingservice.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;

public interface LiveKitService {
    LiveKitRoomResponse createRoom(String roomName);
    List<LiveKitRoomResponse> listRooms();
    Optional<LiveKitRoomResponse> getRoom(String roomName);
    void deleteRoom(String roomName);
    LiveKitRoomResponse ensureRoomExists(String roomName);
    String generateJoinToken(String roomName, String identity, String participantName, Map<String, String> metadata, boolean canPublish, boolean canSubscribe);
    void removeParticipant(String roomName, String liveKitIdentity);
    void removeParticipantsByAttendeeId(String roomName, String attendeeIdPrefix);
}
