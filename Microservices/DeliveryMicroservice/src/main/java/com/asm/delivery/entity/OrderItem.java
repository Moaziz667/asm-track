package com.asm.delivery.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Value object stored inside orders.items JSONB array. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {
    private String id;
    private String sku;
    private String name;
    private Integer quantity;
    private Integer quantityDone;
    private BigDecimal unitWeightKg;
    private BigDecimal unitPrice;

    /** Delivery outcome recorded by the driver: DELIVERED, REFUSED, or DAMAGED. */
    private String outcome;

    /** Reason code when outcome is REFUSED/DAMAGED/MISSING (the failure-reason catalog code). */
    private String reason;

    /**
     * Human label for {@link #reason}, snapshotted from the failure-reason catalog at submission time
     * (like Delivery.failReason). Historically stable — display shows this verbatim, independent of any
     * later rename/deactivation of the motif. Null for legacy/offline codes not in the catalog.
     */
    private String reasonLabel;

    /** Optional driver comment specific to this item. */
    private String comment;

    /** Odoo product type: "product" (storable), "consu" (consumable), "service". Null for legacy items. */
    private String productType;
}
