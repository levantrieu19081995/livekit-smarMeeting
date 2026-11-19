package vn.cusc.meetingservice.exception;

public class MeetingPollNotFoundException extends RuntimeException {
    public MeetingPollNotFoundException(Long pollId) {
        super("Không tìm thấy biểu quyết: " + pollId);
    }
}
