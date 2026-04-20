package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.VehicleResponse;
import com.asm.delivery.dto.response.DepotResponse;
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
@Schema(description = "Route full details including driver, vehicle, depot and all stops with nested full stop data")
public class RouteFullResponse {
    @Schema(description = "Route unique identifier")
    private UUID id;

    @Schema(description = "Route display name")
    private String name;

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

    @Schema(description = "Cumulative route delay in minutes")
    private Integer cumulativeDelayMinutes;

    @Schema(description = "On-time completion rate (%)")
    private Double onTimeCompletionRate;

    @Schema(description = "Route start delay in minutes")
    private Integer routeStartDelayMinutes;

    @Schema(description = "Current active stops with full details")
    private List<RouteStopFullResponse> stops;

    @Schema(description = "Legacy stops removed from active route")
    private List<RouteStopFullResponse> legacyStops;

    // ── Full nested objects ──────────────────────────────────────────────────────
    @Schema(description = "Assigned driver full details")
    private AdminDriverResponse driver;

    @Schema(description = "Assigned vehicle full details")
    private VehicleResponse vehicle;

    @Schema(description = "Departure depot full details")
    private DepotResponse depot;

    // ── Depot & optimization fields ──────────────────────────────────────────────
    @Schema(description = "Route departure datetime")
    private LocalDateTime departureTime;

    @Schema(description = "Total route duration in seconds")
    private Integer totalDurationSeconds;

    @Schema(description = "Total route distance in meters")
    private Integer totalDistanceMeters;

    @Schema(description = "True when OSRM optimization was applied")
    private Boolean isOptimized;

    @Schema(description = "Full route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Joined zone label detected from stops")
    private String detectedZoneLabel;

    @Schema(description = "Distinct detected zones")
    private List<String> detectedZoneNames;

    @Schema(description = "Validation warnings to display in UI")
    private List<String> validationWarnings;

    @Schema(description = "Human-readable plan version — incremented on validate, reassign, add/remove stop")
    private Integer routeVersion;
}
