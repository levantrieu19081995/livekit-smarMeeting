package vn.cusc.meetingservice.exception;

import org.springframework.http.HttpStatus;

public class MeetingQaException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MeetingQaException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
