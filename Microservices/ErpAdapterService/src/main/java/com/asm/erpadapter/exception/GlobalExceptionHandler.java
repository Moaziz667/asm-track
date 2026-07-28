package com.asm.erpadapter.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for all ERP adapter controllers.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ErpAdapterException.class)
    public ResponseEntity<Map<String, Object>> handleErpException(ErpAdapterException e) {
        log.warn("ERP adapter error: {} (status={})", e.getMessage(), e.getStatusCode());
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getMessage(), "status", e.getStatusCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage(), "status", 400));
    }

    /**
     * A body Spring could not parse, or a required parameter the caller omitted, is a malformed
     * <em>request</em> — not a server fault. Without these they fell through to
     * {@link #handleGeneric} and answered {@code 500}, which misreports a client mistake as an
     * outage: it inflates the 5xx rate, fires availability alerts, and buries real failures among
     * caller errors. Logged at WARN for the same reason.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Corps de requête illisible ou JSON invalide.",
                "errorCode", "MALFORMED_REQUEST_BODY", "status", 400));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("Missing request parameter '{}'", e.getParameterName());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Paramètre requis manquant : '" + e.getParameterName() + "'.",
                "errorCode", "MISSING_REQUEST_PARAMETER",
                "parameter", e.getParameterName(), "status", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unexpected error in ERP adapter", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Internal adapter error", "status", 500));
    }
}
