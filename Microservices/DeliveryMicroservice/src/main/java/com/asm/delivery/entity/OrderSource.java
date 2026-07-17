package com.asm.delivery.entity;

/**
 * Where an order originated. Every order comes from an ERP — the legacy client-app source (APP) was
 * removed. The value records WHICH ERP imported it; the active provider is selected separately on the
 * adapter side ({@code erp.provider}).
 */
public enum OrderSource {
    ODOO,
    ERPNEXT,
    DUX;

    /** Map an adapter-provided provider tag (preview {@code source}) to the domain enum; defaults to ODOO. */
    public static OrderSource fromProvider(String provider) {
        if (provider == null) return ODOO;
        return switch (provider.trim().toUpperCase()) {
            case "ERPNEXT" -> ERPNEXT;
            case "DUX" -> DUX;
            default -> ODOO;
        };
    }
}
