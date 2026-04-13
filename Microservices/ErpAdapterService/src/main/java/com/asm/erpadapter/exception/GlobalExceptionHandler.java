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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unexpected error in ERP adapter", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Internal adapter error", "status", 500));
    }
}
