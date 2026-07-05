package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One per-unit disposition of an order line, mirrored from the platform's WMS breakdown. A line of
 * qty N can split into several segments (e.g. DELIVERED×2, REFUSED×1, MISSING×1), each with its own
 * motif. Used only to enrich the Odoo chatter note; the stock move stays driven by {@code quantityDone}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpItemSegmentDTO {
    /** DELIVERED | REFUSED | DAMAGED | MISSING */
    private String disposition;
    private Integer quantity;
    /** Failure-reason code for a non-delivered disposition (null for DELIVERED). */
    private String reasonCode;
    /** Snapshotted human label for {@link #reasonCode}. */
    private String reasonLabel;
    private String comment;
}
