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

    /**
     * Unit price taxes included, carried from the ERP at import.
     *
     * <p>The line's untaxed price above cannot answer the only monetary question asked of this row —
     * what the customer owes for the units actually handed over. Kept per line rather than as one
     * order total so a refusal at the door recomputes correctly instead of showing up as a cash
     * shortfall the driver has to justify.
     *
     * <p>Null on orders imported before this was carried, and on ERPs that expose no taxed figure.
     */
    private BigDecimal unitPriceTtc;

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

    /** Canonical product type (vendor-neutral): STORABLE, CONSUMABLE, or SERVICE. Null for legacy items. */
    private String productType;

    /**
     * Warehouse this line ships from, as the ERP names it.
     *
     * <p>The order carries one too, and for a long time that was assumed to settle it — because it
     * does in Odoo, where a picking belongs to a single warehouse. ERPNext puts a warehouse on each
     * line, so one delivery note can legitimately draw from two places, and reading only the header
     * sent a driver to load in Sousse goods that sit in Monastir.
     *
     * <p>Null on everything imported before this was carried; the route falls back to the order's
     * depot in that case, which is exactly what it used to do.
     */
    private String warehouseCode;

    /** The ASM depot behind {@link #warehouseCode}, resolved once at import. Null when unresolved. */
    private java.util.UUID sourceDepotId;

    /**
     * Per-unit disposition breakdown (WMS-grade). A line can split into DELIVERED / REFUSED / DAMAGED /
     * MISSING segments, each with its own motif. Null/empty for a clean full delivery. The single fields
     * above ({@code quantityDone}, {@code outcome}, {@code reason}…) are derived from this and remain the
     * ERP contract; this list is additive admin metadata.
     */
    private java.util.List<ItemSegment> segments;
}
