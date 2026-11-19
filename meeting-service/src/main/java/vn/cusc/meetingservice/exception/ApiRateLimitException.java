package vn.cusc.meetingservice.exception;

public class ApiRateLimitException extends RuntimeException {
    public ApiRateLimitException() { super("Thao tác quá nhanh. Vui lòng thử lại sau."); }
}
