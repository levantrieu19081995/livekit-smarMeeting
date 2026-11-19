package vn.cusc.meetingservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vn.cusc.meetingservice.exception.ErrorResponse;
import vn.cusc.meetingservice.exception.MeetingQaException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MeetingQaController.class)
public class MeetingQaExceptionHandler {
    @ExceptionHandler(MeetingQaException.class)
    public ResponseEntity<ErrorResponse> handle(MeetingQaException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> invalidInput(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "QA_VALIDATION_ERROR", "Dữ liệu hỏi đáp không hợp lệ.", request);
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(LocalDateTime.now(), status.value(),
            status.getReasonPhrase(), code, message, request.getRequestURI()));
    }
}
