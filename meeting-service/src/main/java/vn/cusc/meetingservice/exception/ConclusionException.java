package vn.cusc.meetingservice.exception;

import org.springframework.http.HttpStatus;

public class ConclusionException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public ConclusionException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
}
