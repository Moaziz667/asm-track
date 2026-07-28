package com.asm.delivery.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders", uniqueConstraints = {
    @UniqueConstraint(name = "orders_erp_order_id_key", columnNames = {"erp_order_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private OrderSource source;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0.0";

    // ── Client ───────────────────────────────────────────────────────────────
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 100)
    private String clientName;

    @Column(name = "client_phone", length = 20)
    private String clientPhone;

    @Column(name = "client_email", length = 100)
    private String clientEmail;

    // ── ERP ──────────────────────────────────────────────────────────────────
    @Column(name = "erp_order_id", length = 100)
    private String erpOrderId;

    @Column(name = "erp_external_ref", length = 100)
    private String erpExternalRef;

    /** Official ERP delivery-note / picking number (bon de livraison) this order maps to. */
    @Column(name = "bl_number", length = 100)
    private String blNumber;

    /** Raw source-warehouse code from the ERP (kept for re-mapping to a depot). */
    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    /** Resolved source depot (where the goods are loaded). Null until mapped/assigned. */
    @Column(name = "source_depot_id")
    private java.util.UUID sourceDepotId;

    // ── Origin ────────────────────────────────────────────────────────────────
    @Column(name = "origin_name", length = 100)
    private String originName;

    @Column(name = "origin_address", columnDefinition = "TEXT")
    private String originAddress;

    @Column(name = "origin_city", length = 100)
    private String originCity;

    @Column(name = "origin_postal_code", length = 20)
    private String originPostalCode;

    @Column(name = "origin_country_code", length = 2)
    private String originCountryCode;

    @Column(name = "origin_contact_name", length = 100)
    private String originContactName;

    @Column(name = "origin_contact_phone", length = 20)
    private String originContactPhone;

    @Column(name = "origin_contact_email", length = 100)
    private String originContactEmail;

    // ── Destination ───────────────────────────────────────────────────────────
    @Column(name = "dropoff_address", nullable = false, columnDefinition = "TEXT")
    private String dropoffAddress;

    @Column(name = "dropoff_city", length = 100)
    private String dropoffCity;

    @Column(name = "dropoff_postal_code", length = 20)
    private String dropoffPostalCode;

    @Column(name = "dropoff_country_code", length = 2)
    @Builder.Default
    private String dropoffCountryCode = "TN";

    @Column(name = "dropoff_lat", precision = 10, scale = 7)
    private BigDecimal dropoffLat;

    @Column(name = "dropoff_lng", precision = 10, scale = 7)
    private BigDecimal dropoffLng;

    @Column(name = "delivery_instructions", columnDefinition = "TEXT")
    private String deliveryInstructions;

    // ── Financial ─────────────────────────────────────────────────────────────
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 3)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "TND";

    // ── Planning ──────────────────────────────────────────────────────────────
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /** Admin-set new scheduled date on replan; overrides the stale ERP date for SLA. Null until replanned. */
    @Column(name = "rescheduled_at")
    private LocalDateTime rescheduledAt;

    /** The commitment SLAs measure against: the replan date if set, else the ERP scheduled date. */
    public LocalDateTime effectiveScheduledAt() {
        return rescheduledAt != null ? rescheduledAt : scheduledAt;
    }

    /**
     * Every order originates from an ERP (ODOO or DUX) — provider-agnostic gate for ERP sync, so a
     * change of provider never needs new conditionals here.
     */
    public boolean isFromErp() {
        return source != null;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private OrderPriority priority = OrderPriority.NORMAL;

    // ── Items (JSONB) ─────────────────────────────────────────────────────────
    @Type(JsonType.class)
    @Column(name = "items", columnDefinition = "jsonb", nullable = false)
    private List<OrderItem> items;

    /**
     * ERP values the integrator mapped that have no field of their own here, keyed by the label they
     * chose ({@code {"Référence interne": "REF-4471"}}).
     *
     * <p>Every customer keeps something in their ERP that ASM has no concept of. Without somewhere for
     * it to land, mapping such a field means the value is read and then dropped — so the integrator
     * either gets nothing, or we grow a column per customer. This is display-only on purpose: ASM
     * cannot sort, filter or reason about what it does not understand, and a field that needs to drive
     * behaviour deserves promoting to a real column rather than hiding in here.
     */
    @Type(JsonType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private java.util.Map<String, Object> customFields;

    @Column(name = "total_quantity", nullable = false)
    @Builder.Default
    private Integer totalQuantity = 0;

    @Column(name = "total_weight_kg", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal totalWeightKg = BigDecimal.ZERO;

    // ── Status ────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    // ── Zone ──────────────────────────────────────────────────────────────────
    @Column(name = "zone_id")
    private UUID zoneId;

    // ── ERP sync state ────────────────────────────────────────────────────────
    @Column(name = "erp_client_id", length = 100)
    private String erpClientId;

    /** SYNCED | PENDING_RETRY | PENDING_CANCEL | SYNC_FAILED */
    @Column(name = "erp_sync_status", length = 40)
    @Builder.Default
    private String erpSyncStatus = "SYNCED";

    /** Odoo stock.picking ID from the last partial delivery (backorder). */
    @Column(name = "erp_backorder_id")
    private Integer erpBackorderId;

    /** Last ERP sync operation attempted (STOCK_FULL | FAILURE | CANCELLATION | …) — drives Resync replay. */
    @Column(name = "last_sync_op", length = 40)
    private String lastSyncOp;

    /** Human-readable reason the last sync failed — shown in the System Health drill-down. */
    @Column(name = "last_sync_error", columnDefinition = "text")
    private String lastSyncError;

    /** Number of failed sync attempts since last SYNCED state. */
    @Column(name = "sync_retry_count", nullable = false)
    @Builder.Default
    private Integer syncRetryCount = 0;

    /** Earliest time the scheduler may attempt the next retry (exponential backoff). */
    @Column(name = "next_sync_retry_at")
    private LocalDateTime nextSyncRetryAt;

    /** Set on backorder orders — points to the original order this was split from. */
    @Column(name = "parent_order_id")
    private UUID parentOrderId;

    // ── Metadata ──────────────────────────────────────────────────────────────
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Single source of truth for the human-readable order reference.
     * ERP-agnostic: works for Odoo (S00091), Dux (DX-00123), and backorders (S00091/BO).
     * Priority: erpOrderId → erpExternalRef → short UUID fallback.
     */
    public String resolveRef() {
        if (erpOrderId != null && !erpOrderId.isBlank()) return erpOrderId;
        if (erpExternalRef != null && !erpExternalRef.isBlank()) return erpExternalRef;
        return id != null ? id.toString().substring(0, 8).toUpperCase() : "UNKNOWN";
    }
}
