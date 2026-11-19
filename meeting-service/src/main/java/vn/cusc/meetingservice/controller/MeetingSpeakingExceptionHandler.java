package vn.cusc.meetingservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vn.cusc.meetingservice.exception.ErrorResponse;
import vn.cusc.meetingservice.exception.MeetingSpeakingException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MeetingSpeakingController.class)
public class MeetingSpeakingExceptionHandler {
    @ExceptionHandler(MeetingSpeakingException.class)
    public ResponseEntity<ErrorResponse> handle(MeetingSpeakingException error, HttpServletRequest request) {
        return response(error.getStatus(), error.getCode(), error.getMessage(), request);
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> invalidInput(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "SPEAKING_INVALID_REQUEST", "Dữ liệu phát biểu không hợp lệ.", request);
    }
    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(LocalDateTime.now(), status.value(),
            status.getReasonPhrase(), code, message, request.getRequestURI()));
    }
}
