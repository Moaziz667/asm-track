package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "companyFilter", condition = "company_id = :companyId")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "driver_id")
    private UUID driverId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 30)
    private FailureCode failureCode;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 10)
    private Role cancelledBy;

    /** True when cancellation occurred after pickup — driver must return parcel to origin depot. */
    @Column(name = "return_to_origin", nullable = false)
    @Builder.Default
    private Boolean returnToOrigin = false;

    // NULL = not a COD order, TRUE = cash collected, FALSE = cash not collected
    @Column(name = "cod_collected")
    private Boolean codCollected;

    @Column(name = "cod_amount_collected", precision = 10, scale = 3)
    private java.math.BigDecimal codAmountCollected;

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
