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

    /** Reason code when outcome is REFUSED or DAMAGED (e.g. CLIENT_ABSENT, WRONG_ITEM). */
    private String reason;

    /** Optional driver comment specific to this item. */
    private String comment;
}
