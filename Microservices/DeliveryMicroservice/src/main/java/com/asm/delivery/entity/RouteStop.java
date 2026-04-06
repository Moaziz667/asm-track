package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "route_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "delivery_id", nullable = false, unique = true)
    private UUID deliveryId;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private RouteStopStatus status = RouteStopStatus.PENDING;

    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── ETA / SLA fields ──────────────────────────────────────────────────────────

    /** Calculated ETA for this stop (cumulative from depot departure). */
    @Column(name = "eta_at")
    private LocalDateTime etaAt;

    /** SLA deadline = etaAt + sla buffer (default 30 min). */
    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    /** When the driver actually arrived at this stop (set by mobile app). */
    @Column(name = "actual_arrival_at")
    private LocalDateTime actualArrivalAt;

    /** Computed SLA status: ON_TIME, AT_RISK, BREACHED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status", length = 20)
    private SlaStatus slaStatus;

    /** Drive duration (seconds) from previous point (depot or previous stop). */
    @Column(name = "drive_duration_seconds")
    private Integer driveDurationSeconds;

    /** Drive distance (meters) from previous point. */
    @Column(name = "drive_distance_meters")
    private Integer driveDistanceMeters;

    /** Time spent at this stop in minutes (default 10). */
    @Builder.Default
    @Column(name = "dwell_minutes", nullable = false)
    private Integer dwellMinutes = 10;

    /**
     * Per-leg OSRM geometry for this stop (JSON array of [lat,lng] pairs).
     * Represents the road path from the previous point (depot or prior stop) to this stop.
     * Used by the frontend map to draw colored polylines per leg.
     */
    @Column(name = "route_geometry", columnDefinition = "TEXT")
    private String routeGeometry;

    // ─────────────────────────────────────────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (dwellMinutes == null) dwellMinutes = 10;
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
