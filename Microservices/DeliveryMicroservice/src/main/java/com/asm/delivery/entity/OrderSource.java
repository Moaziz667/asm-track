package com.asm.delivery.entity;

/**
 * Where an order originated. Every order comes from an ERP — the legacy client-app source (APP) was
 * removed. The value records WHICH ERP imported it; the active provider is selected separately on the
 * adapter side ({@code erp.provider}).
 */
public enum OrderSource {
    ODOO,
    DUX
}
