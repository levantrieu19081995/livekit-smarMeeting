package vn.cusc.meetingservice.exception;

public class MeetingNotFoundException extends RuntimeException {
    public MeetingNotFoundException(Long meetingId) {
        super("Khong tim thay cuoc hop voi id=" + meetingId);
    }
}
