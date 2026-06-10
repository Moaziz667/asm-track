package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "routes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Integer version;

    @Column(name = "parent_route_id")
    private UUID parentRouteId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "planned_start_time", nullable = false)
    private LocalTime plannedStartTime;

    @Column(name = "planned_end_time", nullable = false)
    private LocalTime plannedEndTime;

    @Column(name = "city", length = 100)
    private String city;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private RouteStatus status = RouteStatus.DRAFT;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Depot & optimization fields ───────────────────────────────────────────────

    @Column(name = "depot_id")
    private UUID depotId;

    /** Actual departure time from the depot (defaults to date + plannedStartTime). */
    @Column(name = "departure_time")
    private LocalDateTime departureTime;

    @Column(name = "total_duration_seconds")
    private Integer totalDurationSeconds;

    @Column(name = "total_distance_meters")
    private Integer totalDistanceMeters;

    @Builder.Default
    @Column(name = "is_optimized", nullable = false)
    private Boolean isOptimized = false;

    /** Full OSRM road geometry from depot through all stops, stored as [[lat,lng],...] JSON. */
    @Column(name = "route_geometry", columnDefinition = "TEXT")
    private String routeGeometry;

    /** Cumulative delay in minutes for completed stops: Σ max(0, T5 - EW). */
    @Column(name = "cumulative_delay_minutes")
    private Integer cumulativeDelayMinutes;

    /** On-time completion rate using strict EW boundary. */
    @Column(name = "route_on_time_completion_rate", precision = 5, scale = 2)
    private java.math.BigDecimal routeOnTimeCompletionRate;

    @Column(name = "zone_id")
    private UUID zoneId;

    /** When true, this route is excluded from batch optimization runs. */
    @Builder.Default
    @Column(name = "locked", nullable = false)
    private Boolean locked = false;

    /** Human-readable plan version — incremented on every significant mutation (validate, reassign, add/remove stop). */
    @Column(name = "route_version", nullable = false)
    @Builder.Default
    private Integer routeVersion = 1;

    // ─────────────────────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stopOrder ASC")
    @Builder.Default
    private List<RouteStop> stops = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (plannedStartTime == null) {
            plannedStartTime = LocalTime.of(8, 0);
        }
        if (plannedEndTime == null) {
            plannedEndTime = LocalTime.of(18, 0);
        }
        if (isOptimized == null) {
            isOptimized = false;
        }
        if (locked == null) {
            locked = false;
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
