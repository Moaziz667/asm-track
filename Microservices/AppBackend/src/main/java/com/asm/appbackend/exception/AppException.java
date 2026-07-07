package com.asm.appbackend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    /** Stable machine code for the client to map an error to a field/action (envelope contract). */
    private final String errorCode;

    public AppException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public AppException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, message);
    }

    public static AppException conflict(String errorCode, String message) {
        return new AppException(HttpStatus.CONFLICT, errorCode, message);
    }
}
