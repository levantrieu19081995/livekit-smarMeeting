package vn.cusc.meetingservice.exception;

public class MeetingAccessDeniedException extends RuntimeException {
    public MeetingAccessDeniedException(String message) {
        super(message);
    }
}
