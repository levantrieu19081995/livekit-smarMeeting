package vn.cusc.meetingservice.exception;

public class LiveKitOperationException extends RuntimeException {
    public LiveKitOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public LiveKitOperationException(String message) {
        super(message);
    }
}
