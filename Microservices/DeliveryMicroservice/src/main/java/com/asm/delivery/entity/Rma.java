package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Return Merchandise Authorization — a customer return raised against a delivered shipment.
 * Carries the returned line items and drives a reverse stock move + note back to the ERP
 * once the goods are received and restocked.
 */
@Entity
@Table(name = "rma", indexes = {
        @Index(name = "idx_rma_status", columnList = "status"),
        @Index(name = "idx_rma_delivery", columnList = "delivery_id"),
        @Index(name = "idx_rma_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rma {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "erp_order_id", length = 100)
    private String erpOrderId;

    @Column(name = "bl_number", length = 100)
    private String blNumber;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RmaStatus status = RmaStatus.REQUESTED;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    /**
     * State of the reverse stock-move sync to the ERP. Null until the return is RESTOCKED, then
     * PENDING_SYNC → SYNCED / SYNC_FAILED (set asynchronously by the ERP result consumer). Mirrors
     * the order-level {@code odooSyncStatus} so a failed reverse move never hides behind RESTOCKED.
     */
    @Column(name = "erp_sync_status", length = 40)
    private String erpSyncStatus;

    /** Last ERP reverse-move error, for operator triage when {@code erpSyncStatus = SYNC_FAILED}. */
    @Column(name = "erp_sync_error", columnDefinition = "TEXT")
    private String erpSyncError;

    @OneToMany(mappedBy = "rma", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<RmaItem> items = new ArrayList<>();

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "restocked_at")
    private LocalDateTime restockedAt;

    public void addItem(RmaItem item) {
        item.setRma(this);
        items.add(item);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
