package com.asm.delivery.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException ex, HttpServletRequest request) {
        String path = request != null ? request.getRequestURI() : "";
        String query = request != null ? request.getQueryString() : null;
        if (query != null && !query.isBlank()) {
            path = path + "?" + query;
        }
        String method = request != null ? request.getMethod() : "";
        log.warn("AppException [{}] {} {}: {}", ex.getStatus(), method, path, ex.getMessage());
        
        String errorCode = ex.getErrorCode();
        // Fallback for retro-compatibility
        if ("GENERIC_ERROR".equals(errorCode) && ex.getMessage() != null && ex.getMessage().contains("inspection")) {
            errorCode = "INSPECTION_REQUIRED";
        }
        
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getStatus().value(), ex.getMessage(), errorCode, ex.getErrorParams()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        String message = "Validation failed: " + errors;
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, "VALIDATION_FAILED", errors));
    }

    /**
     * Concurrent writes to the same aggregate (e.g. two drivers confirming the same
     * handoff at once, or a sweep cancelling while a driver confirms). The optimistic
     * {@code @Version} guard lost the race — surface a clean 409 so the client retries
     * rather than seeing a 500.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(),
                        "This action was just updated elsewhere. Please refresh and try again.",
                        "CONCURRENT_UPDATE", Map.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Access denied", "ACCESS_DENIED", Map.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid request parameter";
        if (ex.getName() != null) {
            message = "Invalid value for parameter '" + ex.getName() + "'";
        }
        Map<String, Object> params = new HashMap<>();
        if (ex.getName() != null) {
            params.put("parameter", ex.getName());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, "INVALID_REQUEST_PARAMETER", params));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        
        String errorCode = "INTERNAL_SERVER_ERROR";
        String message = "Internal server error";
        
        Throwable cause = ex;
        while (cause != null) {
            String name = cause.getClass().getName();
            if (name.contains("ConnectException") || name.contains("SocketTimeoutException") || name.contains("UnknownHostException") || name.contains("HttpHostConnectException")) {
                errorCode = "CONNECTION_FAILED";
                message = "Service connectivity failure: Integration network request timed out or refused connection";
                break;
            }
            cause = cause.getCause();
        }
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), message, errorCode, Map.of()));
    }

    @lombok.Getter
    @lombok.Setter
    public static class ErrorResponse {
        private final int status;
        private final String message;
        private final String errorCode;
        private final java.util.Map<String, Object> errorParams;
        private final LocalDateTime timestamp = LocalDateTime.now();

        public ErrorResponse(int status, String message) {
            this(status, message, "GENERIC_ERROR", java.util.Map.of());
        }

        public ErrorResponse(int status, String message, String errorCode) {
            this(status, message, errorCode, java.util.Map.of());
        }

        public ErrorResponse(int status, String message, String errorCode, java.util.Map<String, Object> errorParams) {
            this.status = status;
            this.message = message;
            this.errorCode = errorCode != null ? errorCode : "GENERIC_ERROR";
            this.errorParams = errorParams != null ? errorParams : java.util.Map.of();
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }
    }
}
