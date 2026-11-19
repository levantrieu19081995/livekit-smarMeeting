package vn.cusc.meetingservice.exception;

public class MinutesNotFoundException extends RuntimeException {
    public MinutesNotFoundException(Long meetingId) {
        super("Khong tim thay bien ban cho meeting " + meetingId);
    }

    public MinutesNotFoundException(Long meetingId, Integer version) {
        super("Khong tim thay bien ban version " + version + " cho meeting " + meetingId);
    }
}
