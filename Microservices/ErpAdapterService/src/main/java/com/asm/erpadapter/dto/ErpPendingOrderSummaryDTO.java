package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Summary of a pending ERP order (list view).
 * Does not include line items — use preview endpoint for full details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPendingOrderSummaryDTO {
    private String erpOrderId;
    private String customerRef;
    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String deliveryCity;
    private BigDecimal totalAmount;
    private String currency;
    /** Carried on the list too, so the operator sees which orders come with a collection before importing. */
    private Boolean codRequired;
    private BigDecimal codAmount;
    private LocalDateTime dateOrder;
    private LocalDateTime scheduledAt;

    // ── Delivery-note (bon de livraison) fields — enterprise multi-depot ──────────
    /** Official delivery-note / picking number from the ERP (e.g. Odoo "WH/OUT/00012"). */
    private String blNumber;
    /** Source sale-order reference (e.g. "S00042"). */
    private String saleOrderRef;
    /** Short code of the source warehouse (e.g. "SFAX"). */
    private String warehouseCode;
    /** Human name of the source warehouse. */
    private String warehouseName;
    /** True when ready to ship (Odoo picking state = 'assigned'). */
    private Boolean ready;
    /** True when this picking is a backorder (reliquat) — Odoo {@code backorder_id} is set. */
    /** NORMAL | HIGH — so the list can flag an urgent order before anyone imports it. */
    private String priority;

    private boolean backorder;
    /** BL number of the origin picking this is a backorder of (from {@code backorder_id}). */
    private String originBl;
}
