package com.asm.driver.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(AppException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        // `error` kept for backward compat; `message` + `errorCode` align with the shared error
        // envelope so the client can map a failure to a specific field/action.
        body.put("error", ex.getMessage());
        body.put("message", ex.getMessage());
        if (ex.getErrorCode() != null) {
            body.put("errorCode", ex.getErrorCode());
        }
        body.put("status", ex.getStatus().value());
        if (ex.getRetryAfterSeconds() > 0) {
            body.put("retryAfterSeconds", ex.getRetryAfterSeconds());
            return ResponseEntity.status(ex.getStatus())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                    .body(body);
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", 400);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A body Spring could not parse, or a required parameter the caller omitted, is a malformed
     * <em>request</em> — not a server fault. Without these they fell through to
     * {@link #handleException} and answered {@code 500}, which misreports a client mistake as an
     * outage: it inflates the 5xx rate, fires availability alerts, and buries real failures among
     * caller errors. Logged at WARN for the same reason.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Corps de requête illisible ou JSON invalide.");
        body.put("errorCode", "MALFORMED_REQUEST_BODY");
        body.put("status", 400);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter '{}'", ex.getParameterName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Paramètre requis manquant : '" + ex.getParameterName() + "'.");
        body.put("errorCode", "MISSING_REQUEST_PARAMETER");
        body.put("parameter", ex.getParameterName());
        body.put("status", 400);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Internal server error: " + ex.getMessage());
        body.put("status", 500);
        return ResponseEntity.internalServerError().body(body);
    }
}
