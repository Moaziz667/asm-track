package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RouteResponse {
    private UUID id;
    private String name;
    private UUID driverId;
    private UUID vehicleId;
    private LocalDate date;
    private LocalTime plannedStartTime;
    private LocalTime plannedEndTime;
    private String city;
    private RouteStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime validatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    private Integer totalStops;
    private Integer completedStops;
    private Integer failedStops;
    private Integer partialStops;
    private Integer pendingStops;
    private Double progressPercent;
    private Long etaDriftMinutes;
    private List<RouteStopResponse> stops;

    // ── Depot & optimization fields ───────────────────────────────────────────────
    private UUID depotId;
    private LocalDateTime departureTime;
    private Integer totalDurationSeconds;
    private Integer totalDistanceMeters;
    private Boolean isOptimized;
    /** Full OSRM road geometry as [[lat,lng],...] JSON string. */
    private String routeGeometry;

    // ── Zone fields (auto-detected from stop postal codes) ────────────────────────
    /**
     * Compound label of all zones detected across route stops,
     * e.g. "Grand Tunis · Ariana". Empty string if no zones matched.
     */
    private String detectedZoneLabel;
    /** Individual zone names for multi-zone warning logic. */
    private java.util.List<String> detectedZoneNames;

    /** Non-blocking warnings returned from validate() e.g. zone mismatch. */
    private java.util.List<String> validationWarnings;
}
