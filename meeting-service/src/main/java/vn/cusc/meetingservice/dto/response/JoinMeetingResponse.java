package vn.cusc.meetingservice.dto.response;

public record JoinMeetingResponse(
    String roomName,
    String roomSid,
    String serverUrl,
    String token,
    JoinParticipantResponse participant,
    JoinPermissionsResponse permissions
) {
    public record JoinParticipantResponse(
        String attendeeId,
        String connectionId,
        String liveKitIdentity,
        String displayName,
        String role,
        String chatWriteToken,
        Long pointId,
        String pointName
    ) {
        public JoinParticipantResponse(String attendeeId, String connectionId, String liveKitIdentity,
            String displayName, String role, String chatWriteToken) {
            this(attendeeId, connectionId, liveKitIdentity, displayName, role, chatWriteToken, null, null);
        }
    }

    public record JoinPermissionsResponse(
        boolean canEndMeeting,
        boolean canManageParticipants
    ) {
    }
}
