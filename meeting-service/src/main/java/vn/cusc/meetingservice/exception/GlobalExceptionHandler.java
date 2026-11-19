package vn.cusc.meetingservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "Tài liệu vượt giới hạn dung lượng cho phép.", request.getRequestURI());
    }

    @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> handleConcurrentWrite(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "STATE_CHANGED", "Dữ liệu đã thay đổi. Vui lòng tải lại trước khi lưu.", request.getRequestURI());
    }

    @ExceptionHandler(ApiRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(ApiRateLimitException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60")
            .body(new ErrorResponse(LocalDateTime.now(), 429, "Too Many Requests", "RATE_LIMITED", exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.", request.getRequestURI());
    }

    @ExceptionHandler(MeetingSpeakingException.class)
    public ResponseEntity<ErrorResponse> handleSpeaking(MeetingSpeakingException exception, HttpServletRequest request) {
        return build(exception.getStatus(), exception.getCode(), exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "CONSTRAINT_VIOLATION", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MeetingPollNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePollNotFound(MeetingPollNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POLL_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MeetingPollConflictException.class)
    public ResponseEntity<ErrorResponse> handlePollConflict(MeetingPollConflictException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "POLL_CONFLICT", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MeetingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMeetingNotFound(MeetingNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ParticipantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleParticipantNotFound(ParticipantNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ParticipantAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleParticipantAlreadyExists(ParticipantAlreadyExistsException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "PARTICIPANT_ALREADY_EXISTS", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(DocumentNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MinutesNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMinutesNotFound(MinutesNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "MINUTES_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MinutesJobNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMinutesJobNotFound(MinutesJobNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "MINUTES_JOB_NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MeetingAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(MeetingAccessDeniedException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "MEETING_ACCESS_DENIED", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessValidationException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BUSINESS_VALIDATION_ERROR", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(LiveKitOperationException.class)
    public ResponseEntity<ErrorResponse> handleLiveKit(LiveKitOperationException exception, HttpServletRequest request) {
        LOGGER.warn("LiveKit request failed: {}", request.getRequestURI(), exception);
        return build(HttpStatus.BAD_GATEWAY, "LIVEKIT_ERROR", "Không thể hoàn tất thao tác phòng họp. Vui lòng thử lại.", request.getRequestURI());
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAi(AiServiceException exception, HttpServletRequest request) {
        LOGGER.warn("AI request failed: {}", request.getRequestURI(), exception);
        return build(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", "Dịch vụ xử lý đang không sẵn sàng. Vui lòng thử lại.", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception exception, HttpServletRequest request) {
        LOGGER.error("Request failed: {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.", request.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        String message = "Không thể xử lý dữ liệu yêu cầu.";
        String code = "DATA_INTEGRITY_VIOLATION";
        HttpStatus status = HttpStatus.CONFLICT;

        if (exception.getMostSpecificCause() != null) {
            String details = exception.getMostSpecificCause().getMessage();
            if (details != null && details.contains("uk_meeting_user")) {
                message = "Người tham gia này đã tồn tại trong cuộc họp.";
                code = "PARTICIPANT_ALREADY_EXISTS";
            }
        }

        return build(status, code, message, request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status).body(new ErrorResponse(
            LocalDateTime.now(),
            status.value(),
            status.getReasonPhrase(),
            code,
            message,
            path
        ));
    }
}
