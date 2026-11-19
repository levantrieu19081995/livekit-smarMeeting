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

/** Invalid JSON/path types are client errors; never expose parser details from new POINT fields. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MeetingController.class)
public class MeetingInputExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalidInput(Exception error, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(LocalDateTime.now(), status.value(),
            status.getReasonPhrase(), "MEETING_INVALID_REQUEST", "Thông tin cuộc họp hoặc tham gia không hợp lệ.", request.getRequestURI()));
    }
}
