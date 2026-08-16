package com.asm.erpadapter.mapping.type;

/**
 * The Java shape ASM stores a {@link com.asm.erpadapter.mapping.CanonicalField} in.
 *
 * <p>Part of the canonical contract, and therefore <b>not configurable per tenant</b>. A tenant
 * chooses <em>where</em> a value comes from — the source path, the read mode, the ERP field — never
 * <em>what it is</em>. Letting one tenant declare a quantity decimal and another integer would mean
 * every consumer downstream (routing, SLA, the driver app, the ERP write-back) has to branch on the
 * tenant, which is the end of a shared model. When a real business difference needs another type, it
 * is a different canonical field, not the same field with a per-tenant type.
 *
 * <p>Declared here rather than inferred from whichever helper an adapter happens to call: that
 * inference is what let Odoo and ERPNext answer the same question differently with nothing to catch
 * it.
 */
public enum CanonicalType {

    /** Free text. The widest target: anything readable can be written down. */
    TEXT,

    /** Whole number. Quantities of discrete items. */
    INTEGER,

    /** Exact decimal — money and weights, where binary floating point is not acceptable. */
    DECIMAL,

    /** True/false. */
    BOOLEAN,

    /** A moment. ASM stores a {@code LocalDateTime}; a date-only source lands at start of day. */
    DATE_TIME
}
