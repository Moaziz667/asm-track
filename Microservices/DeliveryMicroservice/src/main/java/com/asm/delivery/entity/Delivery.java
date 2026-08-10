package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // A sale order has many shipments (original + backorders), so this is ManyToOne.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Odoo delivery-note (picking) number this shipment fulfils, e.g. "WH/OUT/00012". */
    @Column(name = "bl_number", length = 100)
    private String blNumber;

    /** Odoo backorder picking id this shipment must validate (set on backorder shipments). */
    @Column(name = "erp_backorder_id")
    private Integer erpBackorderId;

    @Column(name = "driver_id")
    private UUID driverId;

    /** Source depot the goods are loaded from (mirrors Order.sourceDepotId for fast route queries). */
    @Column(name = "source_depot_id")
    private UUID sourceDepotId;

    /** FORWARD (depot→client, default) or RETURN_PICKUP (client→depot, a reverse leg for an RMA). ADR-033. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private DeliveryKind kind = DeliveryKind.FORWARD;

    /** For RETURN_PICKUP: the RMA this collection leg fulfils. */
    @Column(name = "rma_id")
    private UUID rmaId;

    /** For RETURN_PICKUP: the depot the collected goods are dropped at (destination of the reverse leg). */
    @Column(name = "return_depot_id")
    private UUID returnDepotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.UNSCHEDULED;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "waiting_sla_minutes")
    private Integer waitingSlaMinutes;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "assign_sla_minutes")
    private Integer assignSlaMinutes;

    @Column(name = "in_transit_at")
    private LocalDateTime inTransitAt;

    @Column(name = "pickup_sla_minutes")
    private Integer pickupSlaMinutes;

    @Column(name = "route_geometry", columnDefinition = "TEXT")
    private String routeGeometry;

    @Column(name = "route_distance_km", precision = 10, scale = 3)
    private java.math.BigDecimal routeDistanceKm;

    @Column(name = "route_duration_minutes")
    private Integer routeDurationMinutes;

    @Column(name = "route_eta_at")
    private LocalDateTime routeEtaAt;

    @Column(name = "transit_sla_minutes_computed")
    private Integer transitSlaMinutesComputed;

    @Column(name = "route_last_computed_at")
    private LocalDateTime routeLastComputedAt;

    @Column(name = "route_provider", length = 20)
    private String routeProvider;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "fail_reason", columnDefinition = "TEXT")
    private String failReason;

    // The driver's free-text failure comment, kept apart from the admin reason label (fail_reason
    // remains the flattened "label — comment" form for ERP/analytics). Null when none was entered.
    @Column(name = "failure_comment", columnDefinition = "TEXT")
    private String failureComment;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 30)
    private FailureCode failureCode;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 10)
    private Role cancelledBy;

    /**
     * When a dispatcher declared this delivery's exception handled.
     *
     * <p>For the handful nothing can deduce — a customer who cancelled by telephone, refused the
     * remainder for good, or an address that turned out not to exist. Everything else retires
     * itself once its follow-up appears.
     *
     * <p>Never final: it is compared against {@link #updatedAt}, so the moment the delivery moves
     * again the exception comes back. Silencing today's problem must not silence tomorrow's on the
     * same shipment.
     */
    @Column(name = "ops_acknowledged_at")
    private LocalDateTime opsAcknowledgedAt;

    @Column(name = "ops_acknowledged_by")
    private UUID opsAcknowledgedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
