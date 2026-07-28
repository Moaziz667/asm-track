package com.asm.erpadapter.mapping;

/**
 * The business data ASM Track imports from an ERP, named independently of any ERP.
 *
 * <p>This is the vocabulary the integrator maps <em>into</em>: "where, in your Odoo, do I find the
 * customer's phone number?". It is deliberately provider-neutral — Odoo answers {@code partner_id.phone}
 * and ERPNext answers {@code customer.mobile_no}, but both fill {@link #CUSTOMER_PHONE}. Keeping the
 * vocabulary shared is what lets one mapping screen serve every provider; if each adapter invented its
 * own field names the UI would have to special-case them and the two would drift apart.
 *
 * <p>Distinct from {@code CanonicalCapability}, which is about <b>making the ERP act</b> (validate a
 * transfer, create a return) and varies by ERP <em>version</em>. These vary by <em>customer</em>, are
 * read-only, and cannot be auto-detected: no probe can tell that {@code x_ref_client} is the customer
 * reference. A human has to say so — hence the mapping UI.
 *
 * @see FieldMappingResolver
 */
public enum CanonicalField {

    // ── Identity ──────────────────────────────────────────────────────────────────────────────────
    /** The reference the import is keyed on; must be unique and stable in the ERP. */
    ERP_ORDER_ID(Scope.HEADER),
    /** Delivery-note number shown to the driver and the recipient. */
    BL_NUMBER(Scope.HEADER),
    /** The originating sales order, for tracing back from a delivery. */
    SALE_ORDER_REF(Scope.HEADER),
    /** The customer's own reference for this order, printed on paperwork. */
    EXTERNAL_REF(Scope.HEADER),

    // ── Recipient ─────────────────────────────────────────────────────────────────────────────────
    CUSTOMER_NAME(Scope.HEADER),
    CUSTOMER_PHONE(Scope.HEADER),
    DELIVERY_ADDRESS(Scope.HEADER),
    DELIVERY_CITY(Scope.HEADER),
    /** Free text the driver sees on arrival (door code, floor, "call before"). */
    DELIVERY_INSTRUCTIONS(Scope.HEADER),

    // ── Commercial ────────────────────────────────────────────────────────────────────────────────
    TOTAL_AMOUNT(Scope.HEADER),
    CURRENCY(Scope.HEADER),
    PAYMENT_TERM_NAME(Scope.HEADER),

    // ── Planning ──────────────────────────────────────────────────────────────────────────────────
    DATE_ORDER(Scope.HEADER),
    /** The promised delivery date; drives SLA and route planning. */
    SCHEDULED_AT(Scope.HEADER),
    /** Dispatch priority. Hardcoded to NORMAL before mapping existed — a prime candidate to map. */
    PRIORITY(Scope.HEADER),

    // ── Fulfilment source ─────────────────────────────────────────────────────────────────────────
    WAREHOUSE_CODE(Scope.HEADER),
    WAREHOUSE_NAME(Scope.HEADER),
    /** Whether the ERP considers the shipment ready to leave; gates import. */
    READY(Scope.HEADER),

    // ── Order lines ───────────────────────────────────────────────────────────────────────────────
    ITEM_SKU(Scope.LINE),
    ITEM_NAME(Scope.LINE),
    ITEM_QUANTITY(Scope.LINE),
    ITEM_UNIT_PRICE(Scope.LINE),
    ITEM_UNIT_WEIGHT_KG(Scope.LINE),
    ITEM_PRODUCT_TYPE(Scope.LINE);

    /** Whether the field belongs to the order header (once) or to each order line (N times). */
    public enum Scope { HEADER, LINE }

    private final Scope scope;

    CanonicalField(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    public boolean isLine() {
        return scope == Scope.LINE;
    }
}
