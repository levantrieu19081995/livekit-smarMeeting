package vn.cusc.meetingservice.exception;

public class MinutesJobNotFoundException extends RuntimeException {
    public MinutesJobNotFoundException(Long meetingId) {
        super("Khong tim thay processing job cho meeting " + meetingId);
    }
}
