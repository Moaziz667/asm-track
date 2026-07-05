package com.asm.delivery.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One disposition of a per-unit breakdown for a single order line. A line of qty N can split into
 * several segments, e.g. {DELIVERED×2, REFUSED×1 (motif A), DAMAGED×1 (motif B)}. Stored inside the
 * OrderItem JSONB; the line's denormalized {@code quantityDone}/{@code outcome}/{@code reason} (which
 * the ERP sync reads) are derived from these segments, so the breakdown never changes the ERP contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemSegment {
    /** DELIVERED | REFUSED | DAMAGED | MISSING */
    private String disposition;
    private Integer quantity;
    /** Failure-reason code for a non-delivered disposition (null for DELIVERED). */
    private String reasonCode;
    /** Snapshotted human label for {@link #reasonCode}. */
    private String reasonLabel;
    private String comment;
}
