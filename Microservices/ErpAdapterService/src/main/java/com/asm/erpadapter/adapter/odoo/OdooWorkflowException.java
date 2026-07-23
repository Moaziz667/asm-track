package com.asm.erpadapter.adapter.odoo;

/**
 * Thrown when an Odoo workflow encounters an unexpected or unsupported response,
 * such as an unknown wizard type from {@code button_validate}.
 *
 * <p>This is a fail-fast signal — the caller must not silently ignore it.
 */
public class OdooWorkflowException extends RuntimeException {

    private final String operation;
    private final String details;

    public OdooWorkflowException(String operation, String details) {
        super(operation + ": " + details);
        this.operation = operation;
        this.details = details;
    }

    public OdooWorkflowException(String operation, String details, Throwable cause) {
        super(operation + ": " + details, cause);
        this.operation = operation;
        this.details = details;
    }

    public String getOperation() {
        return operation;
    }

    public String getDetails() {
        return details;
    }
}
