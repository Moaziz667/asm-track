package com.asm.erpadapter.adapter.odoo;

/**
 * Names the business capabilities ASM Track needs from the ERP.
 *
 * <p>This enum contains <b>only names</b> — no model, type, or candidate fields/methods.
 * That metadata lives in {@code odoo-capabilities.json} (the Capability Registry).
 *
 * <p>Workflow code asks for a capability by name:
 * <pre>
 *   String field = capabilityResolver.resolve(DONE_QUANTITY);
 * </pre>
 */
public enum CanonicalCapability {

    DONE_QUANTITY,
    DELIVERY_VALIDATE,
    CREATE_RETURN,
    CANCEL_DELIVERY,
    UNLOCK_SALE_ORDER,
    RESERVE_STOCK,
    FORCE_AVAILABILITY,
    SET_FULL_QUANTITY,
    CONFIRM_SALE_ORDER,
    BACKORDER_CONFIRM,
    SMS_CONFIRM,
    IMMEDIATE_TRANSFER
}
