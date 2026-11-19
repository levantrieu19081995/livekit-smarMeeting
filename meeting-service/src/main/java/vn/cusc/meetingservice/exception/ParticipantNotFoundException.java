package vn.cusc.meetingservice.exception;

public class ParticipantNotFoundException extends RuntimeException {
    public ParticipantNotFoundException(Long meetingId, String userId) {
        super("Khong tim thay participant " + userId + " trong meeting " + meetingId);
    }
}
