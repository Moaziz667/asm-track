package com.asm.delivery.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartialDeliveryItem {
    private String sku;
    @JsonAlias({"productId"})
    private String productId;
    @JsonAlias({"itemId", "id"})
    private String itemId;
    private Integer quantityDone;

    /**
     * Per-item delivery outcome: DELIVERED, REFUSED, or DAMAGED.
     * Optional — when absent, inferred from quantityDone (0 → REFUSED, >0 → DELIVERED).
     */
    private String outcome;

    /**
     * Reason code when outcome is REFUSED or DAMAGED.
     * Values: CLIENT_ABSENT, CLIENT_REJECTED, DAMAGED, WRONG_ITEM, POSTPONED.
     */
    private String reason;

    /** Optional driver comment specific to this item. */
    private String comment;

    /** Item display name — populated server-side from OrderItem.name for Odoo notes. */
    private String name;

    /** Human label for {@link #reason}, resolved server-side from the catalog for Odoo notes. */
    private String reasonLabel;

    public PartialDeliveryItem(String sku, Integer quantityDone) {
        this.sku = sku;
        this.quantityDone = quantityDone;
    }

    /** Resolves the best available reference key to match against an ERP product SKU. */
    public String referenceKey() {
        if (sku != null && !sku.isBlank()) return sku.trim();
        if (productId != null && !productId.isBlank()) return productId.trim();
        if (itemId != null && !itemId.isBlank()) return itemId.trim();
        return null;
    }

    /**
     * Returns the effective outcome, inferring from quantityDone when not explicitly set.
     * Inference: quantityDone > 0 → DELIVERED, quantityDone == 0 → REFUSED.
     */
    public String effectiveOutcome() {
        if (outcome != null && !outcome.isBlank()) return outcome.toUpperCase();
        return (quantityDone != null && quantityDone > 0) ? "DELIVERED" : "REFUSED";
    }
}
