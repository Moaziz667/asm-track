package com.asm.erpadapter.exception;

/**
 * Generic exception for ERP adapter failures.
 * Carries an HTTP status code for the controller to return.
 */
public class ErpAdapterException extends RuntimeException {

    private final int statusCode;

    public ErpAdapterException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ErpAdapterException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public static ErpAdapterException notFound(String message) {
        return new ErpAdapterException(message, 404);
    }

    public static ErpAdapterException badRequest(String message) {
        return new ErpAdapterException(message, 400);
    }

    public static ErpAdapterException internal(String message) {
        return new ErpAdapterException(message, 500);
    }
}
