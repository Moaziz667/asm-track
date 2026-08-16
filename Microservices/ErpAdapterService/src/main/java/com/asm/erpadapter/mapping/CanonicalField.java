package com.asm.erpadapter.mapping;

import com.asm.erpadapter.mapping.type.CanonicalType;

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
    ERP_ORDER_ID(Scope.HEADER, CanonicalType.TEXT),
    /** Delivery-note number shown to the driver and the recipient. */
    BL_NUMBER(Scope.HEADER, CanonicalType.TEXT),
    /** The originating sales order, for tracing back from a delivery. */
    SALE_ORDER_REF(Scope.HEADER, CanonicalType.TEXT),
    /** The customer's own reference for this order, printed on paperwork. */
    CUSTOMER_REF(Scope.HEADER, CanonicalType.TEXT),

    // ── Recipient ─────────────────────────────────────────────────────────────────────────────────
    CUSTOMER_NAME(Scope.HEADER, CanonicalType.TEXT),
    CUSTOMER_PHONE(Scope.HEADER, CanonicalType.TEXT),
    DELIVERY_ADDRESS(Scope.HEADER, CanonicalType.TEXT),
    DELIVERY_CITY(Scope.HEADER, CanonicalType.TEXT),
    /**
     * The recipient's postal code — the key ASM resolves a delivery's zone from.
     *
     * <p>Every ERP carries it and none of it reached ASM: the code was read only to be
     * concatenated into the address string, so zoning fell back to reverse-geocoding, which
     * runs solely for orders that arrive without coordinates. An integration that supplies
     * exact coordinates — the good case — therefore produced deliveries with no zone at all.
     */
    DELIVERY_POSTAL_CODE(Scope.HEADER, CanonicalType.TEXT),
    /** Free text the driver sees on arrival (door code, floor, "call before"). */
    DELIVERY_INSTRUCTIONS(Scope.HEADER, CanonicalType.TEXT),

    // ── Commercial ────────────────────────────────────────────────────────────────────────────────
    TOTAL_AMOUNT(Scope.HEADER, CanonicalType.DECIMAL),
    CURRENCY(Scope.HEADER, CanonicalType.TEXT),

    /**
     * Whether the driver must collect payment on arrival.
     *
     * <p>Deliberately <b>not</b> inferred from payment terms. An empty {@code payment_term_id} means
     * "immediate" in Odoo, but tenants leave it empty by accident all the time, and the two failure
     * modes are not symmetric: a driver who collects nothing when he should creates an invoice to
     * chase, while a driver who demands money he should not creates an incident with the customer and
     * a sum nobody can account for. So the default is "do not collect", and collecting is something a
     * human has to switch on by mapping this field.
     */
    COD_REQUIRED(Scope.HEADER, CanonicalType.BOOLEAN),
    /** How much to collect when {@link #COD_REQUIRED} is true. */
    COD_AMOUNT(Scope.HEADER, CanonicalType.DECIMAL),

    // ── Planning ──────────────────────────────────────────────────────────────────────────────────
    DATE_ORDER(Scope.HEADER, CanonicalType.DATE_TIME),
    /** The promised delivery date; drives SLA and route planning. */
    SCHEDULED_AT(Scope.HEADER, CanonicalType.DATE_TIME),
    /** Dispatch priority. Hardcoded to NORMAL before mapping existed — a prime candidate to map. */
    PRIORITY(Scope.HEADER, CanonicalType.TEXT),

    // ── Fulfilment source ─────────────────────────────────────────────────────────────────────────
    WAREHOUSE_CODE(Scope.HEADER, CanonicalType.TEXT),
    WAREHOUSE_NAME(Scope.HEADER, CanonicalType.TEXT),
    /** Whether the ERP considers the shipment ready to leave; gates import. */
    READY(Scope.HEADER, CanonicalType.BOOLEAN),

    // ── Order lines ───────────────────────────────────────────────────────────────────────────────
    ITEM_SKU(Scope.LINE, CanonicalType.TEXT),
    ITEM_NAME(Scope.LINE, CanonicalType.TEXT),
    ITEM_QUANTITY(Scope.LINE, CanonicalType.INTEGER),
    ITEM_UNIT_PRICE(Scope.LINE, CanonicalType.DECIMAL),
    ITEM_UNIT_WEIGHT_KG(Scope.LINE, CanonicalType.DECIMAL),
    ITEM_PRODUCT_TYPE(Scope.LINE, CanonicalType.TEXT),
    /**
     * Which warehouse this particular line ships from.
     *
     * <p>{@link #WAREHOUSE_CODE} answers the same question for the note as a whole, and for a long
     * while that was taken to be the whole truth — because it is, in Odoo, where a picking belongs to
     * one warehouse and every line follows. ERPNext puts a {@code warehouse} on each Delivery Note
     * Item, so one note can legitimately draw from two places; reading only the header attached the
     * lot to the first, and a driver would load in Sousse goods that sit in Monastir.
     *
     * <p>Line scope is what makes the model portable: an adapter is never asked to reshape its ERP,
     * only to answer, per line, where the goods are. An ERP with a single warehouse per document
     * simply repeats the header value, and the route ends up with one pickup as before.
     */
    ITEM_WAREHOUSE_CODE(Scope.LINE, CanonicalType.TEXT);

    /** Whether the field belongs to the order header (once) or to each order line (N times). */
    public enum Scope { HEADER, LINE }

    private final Scope scope;
    private final CanonicalType type;

    CanonicalField(Scope scope, CanonicalType type) {
        this.scope = scope;
        this.type = type;
    }

    public Scope scope() {
        return scope;
    }

    /**
     * The Java shape this field is stored in — part of the canonical contract, identical for every
     * tenant and every ERP.
     *
     * <p>Declared here so exactly one place answers it. Before, the answer was implied by whichever
     * coercion helper an adapter happened to call, which meant Odoo and ERPNext could disagree about
     * the same field with nothing to notice. It is also what
     * {@link com.asm.erpadapter.mapping.type.CanonicalConverterRegistry} checks at start-up: a type no
     * converter produces stops the application rather than importing blanks.
     */
    public CanonicalType type() {
        return type;
    }

    public boolean isLine() {
        return scope == Scope.LINE;
    }
}
