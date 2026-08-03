package com.asm.erpadapter.adapter.odoo.workflow;

/**
 * Thrown when a sale order does not exist in Odoo.
 * Used to short-circuit retry loops (non-retryable error).
 */
public class SaleOrderNotFoundException extends RuntimeException {
    private final Integer erpOrderId;

    public SaleOrderNotFoundException(Integer erpOrderId) {
        super("Sale order not found in Odoo: " + erpOrderId);
        this.erpOrderId = erpOrderId;
    }

    public Integer getErpOrderId() { return erpOrderId; }
}
