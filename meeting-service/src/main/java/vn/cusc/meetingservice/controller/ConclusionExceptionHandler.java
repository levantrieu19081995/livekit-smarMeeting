package vn.cusc.meetingservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.cusc.meetingservice.exception.ConclusionException;

@RestControllerAdvice
public class ConclusionExceptionHandler {
    @ExceptionHandler(ConclusionException.class)
    public ResponseEntity<?> handle(ConclusionException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(Map.of("status", error.status().value(),
            "code", error.code(), "message", error.getMessage(), "path", request.getRequestURI()));
    }
}
