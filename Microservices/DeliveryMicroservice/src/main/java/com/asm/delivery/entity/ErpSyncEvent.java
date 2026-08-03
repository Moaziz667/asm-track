package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One ERP sync attempt, as it happened. Append-only: rows are written once and never updated.
 *
 * <p>The {@code orders} table keeps only the <em>latest</em> outcome per order, which cannot answer
 * "how often does this ERP reject us?" or "what happened to that BL last week?" — a failure that is
 * later retried successfully vanishes. This journal keeps every attempt so the operator console can
 * show a history instead of a snapshot.
 *
 * <p>Provider-agnostic: written from the single point where all sync results converge, and stamped
 * with the tenant's configured provider, so Odoo and ERPNext (and whatever follows) journal alike.
 */
@Entity
@Table(name = "erp_sync_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErpSyncEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** The tenant's ERP at the time of the attempt ("odoo", "erpnext"), or null if unconfigured. */
    @Column(length = 40)
    private String provider;

    /** The sync operation: DELIVER, PARTIAL, RETURN, … Mirrors {@code orders.last_sync_op}. */
    @Column(length = 40)
    private String op;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_reason", columnDefinition = "text")
    private String errorReason;

    /** Set for order syncs; null for returns. */
    @Column(name = "order_id")
    private UUID orderId;

    /** Set for return (RMA) syncs; null for orders. */
    @Column(name = "rma_id")
    private UUID rmaId;

    /** What the operator recognises the row by — the BL number, else the ERP reference. */
    @Column(length = 120)
    private String reference;
}
