package com.asm.delivery.exception;

import org.springframework.http.HttpStatus;
import java.util.Map;

/** Application-level exception that maps directly to an HTTP status. */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> errorParams;

    public AppException(HttpStatus status, String message) {
        this(status, "GENERIC_ERROR", message, Map.of());
    }

    public AppException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Map.of());
    }

    public AppException(HttpStatus status, String errorCode, String message, Map<String, Object> errorParams) {
        super(message);
        this.status = status;
        this.errorCode = errorCode != null ? errorCode : "GENERIC_ERROR";
        this.errorParams = errorParams != null ? errorParams : Map.of();
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getErrorParams() {
        return errorParams;
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static AppException notFound(String errorCode, String message) {
        return new AppException(HttpStatus.NOT_FOUND, errorCode, message);
    }

    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    public static AppException conflict(String errorCode, String message) {
        return new AppException(HttpStatus.CONFLICT, errorCode, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static AppException forbidden(String errorCode, String message) {
        return new AppException(HttpStatus.FORBIDDEN, errorCode, message);
    }

    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static AppException badRequest(String errorCode, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    public static AppException badRequest(String errorCode, String message, Map<String, Object> params) {
        return new AppException(HttpStatus.BAD_REQUEST, errorCode, message, params);
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static AppException unauthorized(String errorCode, String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, errorCode, message);
    }

    public static AppException serviceUnavailable(String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }

    public static AppException serviceUnavailable(String errorCode, String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, errorCode, message);
    }

    public static AppException unprocessableEntity(String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_ENTITY", message);
    }

    public static AppException unprocessableEntity(String errorCode, String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }
}
