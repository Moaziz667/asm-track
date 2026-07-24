package com.asm.driver.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final long retryAfterSeconds;
    /** Stable machine code for the client to map an error to a field/action (envelope contract). */
    private final String errorCode;

    public AppException(HttpStatus status, String message) {
        this(status, message, 0L);
    }

    public AppException(HttpStatus status, String message, long retryAfterSeconds) {
        this(status, null, message, retryAfterSeconds);
    }

    public AppException(HttpStatus status, String errorCode, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, message);
    }

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, message);
    }

    public static AppException notFound(String errorCode, String message) {
        return new AppException(HttpStatus.NOT_FOUND, errorCode, message, 0L);
    }

    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT, message);
    }

    public static AppException conflict(String errorCode, String message) {
        return new AppException(HttpStatus.CONFLICT, errorCode, message, 0L);
    }

    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, message);
    }

    public static AppException badRequest(String errorCode, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, errorCode, message, 0L);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, message);
    }

    public static AppException tooManyRequests(String message, long retryAfterSeconds) {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, message, retryAfterSeconds);
    }

    public static AppException internal(String message) {
        return new AppException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
