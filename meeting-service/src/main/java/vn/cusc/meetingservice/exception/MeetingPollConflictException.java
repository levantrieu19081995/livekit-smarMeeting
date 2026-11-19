package vn.cusc.meetingservice.exception;

public class MeetingPollConflictException extends RuntimeException {
    public MeetingPollConflictException(String message) {
        super(message);
    }
}
