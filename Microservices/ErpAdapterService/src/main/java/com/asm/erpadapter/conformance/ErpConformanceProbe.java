package com.asm.erpadapter.conformance;

/**
 * Certifies that a tenant's live ERP instance supports the exact contract the ASM adapter needs.
 * One implementation per ERP family ({@code odoo}, {@code erpnext}); the {@link
 * com.asm.erpadapter.routing.ErpProviderRouter} selects the right one from the current tenant's settings.
 *
 * <p>Implementations MUST be strictly read-only — {@code fields_get}, {@code check_access_rights},
 * version reads only — so the probe is safe to run against a client's production ERP at any time.
 */
public interface ErpConformanceProbe {

    /** Lowercase provider key this probe certifies, e.g. {@code "odoo"}. Used by the router to select it. */
    String provider();

    /** Run the read-only conformance checks against the current tenant's ERP and return the report. */
    ConformanceReport probe();
}
