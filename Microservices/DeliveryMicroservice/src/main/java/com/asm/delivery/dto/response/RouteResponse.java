package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Route aggregate contract used by admin planning and route detail screens")
public class RouteResponse {
    @Schema(description = "Route unique identifier")
    private UUID id;

    @Schema(description = "Owning company id")
    private UUID companyId;

    @Schema(description = "Route display name")
    private String name;

    @Schema(description = "Assigned driver id")
    private UUID driverId;

    @Schema(description = "Assigned vehicle id")
    private UUID vehicleId;

    @Schema(description = "Route operational date")
    private LocalDate date;

    @Schema(description = "Planned route start time")
    private LocalTime plannedStartTime;

    @Schema(description = "Planned route end time")
    private LocalTime plannedEndTime;

    @Schema(description = "Operational city")
    private String city;

    @Schema(description = "Route lifecycle status")
    private RouteStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime validatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    @Schema(description = "Total number of route stops")
    private Integer totalStops;

    @Schema(description = "Completed stops count")
    private Integer completedStops;

    @Schema(description = "Failed stops count")
    private Integer failedStops;

    @Schema(description = "Partial stops count")
    private Integer partialStops;

    @Schema(description = "Pending stops count")
    private Integer pendingStops;

    @Schema(description = "Route progress percentage")
    private Double progressPercent;

    @Schema(description = "Current ETA drift in minutes")
    private Long etaDriftMinutes;

    @Schema(description = "Cumulative route delay in minutes: sum of max(0, T5-EW) on completed stops")
    private Integer cumulativeDelayMinutes;

    @Schema(description = "On-time completion rate (%) using strict EW rule")
    private Double onTimeCompletionRate;

    @Schema(description = "Current active stops")
    private List<RouteStopResponse> stops;

    // ── Route start delay tracking ────────────────────────────────────────────────
    /** Minutes late driver started vs plannedStartTime (null if on time or not yet started) */
    @Schema(description = "Route start delay in minutes")
    private Integer routeStartDelayMinutes;
    /** Legacy stops (reassigned/replanned) for historical display */
    @Schema(description = "Legacy stops removed from active route")
    private List<RouteStopResponse> legacyStops;

    // ── Depot & optimization fields ───────────────────────────────────────────────
    @Schema(description = "Departure depot id")
    private UUID depotId;

    @Schema(description = "Route departure datetime")
    private LocalDateTime departureTime;

    @Schema(description = "Total route duration in seconds")
    private Integer totalDurationSeconds;

    @Schema(description = "Total route distance in meters")
    private Integer totalDistanceMeters;

    @Schema(description = "True when OSRM optimization was applied")
    private Boolean isOptimized;
    /** Full OSRM road geometry as [[lat,lng],...] JSON string. */
    @Schema(description = "Full route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    // ── Zone fields (auto-detected from stop postal codes) ────────────────────────
    /**
     * Compound label of all zones detected across route stops,
     * e.g. "Grand Tunis · Ariana". Empty string if no zones matched.
     */
    @Schema(description = "Joined zone label detected from stops")
    private String detectedZoneLabel;
    /** Individual zone names for multi-zone warning logic. */
    @Schema(description = "Distinct detected zones")
    private java.util.List<String> detectedZoneNames;

    /** Non-blocking warnings returned from validate() e.g. zone mismatch. */
    @Schema(description = "Validation warnings to display in UI")
    private java.util.List<String> validationWarnings;

    @Schema(description = "Human-readable plan version — incremented on validate, reassign, add/remove stop")
    private Integer routeVersion;

    @Schema(description = "When true, route is excluded from batch optimization runs")
    private Boolean locked;
}
