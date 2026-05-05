package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.SlaStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Route stop contract with SLA, ETA and delivery context")
public class RouteStopResponse {
    @Schema(description = "Route stop id")
    private UUID id;

    @Schema(description = "Linked delivery id")
    private UUID deliveryId;

    @Schema(description = "Sequence order in route")
    private Integer stopOrder;

    @Schema(description = "Route stop execution status")
    private RouteStopStatus status;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String notes;
    @Schema(description = "Stop delivery address")
    private String deliveryAddress;

    @Schema(description = "Stop delivery city")
    private String deliveryCity;

    @Schema(description = "Stop postal code")
    private String deliveryPostalCode;

    @Schema(description = "Stop country code")
    private String deliveryCountryCode;

    @Schema(description = "Dropoff latitude")
    private BigDecimal dropoffLat;

    @Schema(description = "Dropoff longitude")
    private BigDecimal dropoffLng;

    @Schema(description = "True when coordinates are pinned")
    private boolean dropoffPinned;

    @Schema(description = "Geometry to this stop as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Distance to stop in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Duration to stop in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "ETA for this stop")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Computed transit SLA in minutes for linked delivery")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Routing provider")
    private String routeProvider;

    // ── ETA / SLA fields ─────────────────────────────────────────────────────────
    @Schema(description = "ETA at stop level")
    private LocalDateTime etaAt;

    @Schema(description = "SLA breach deadline")
    private LocalDateTime slaDeadline;

    @Schema(description = "Current SLA status")
    private SlaStatus slaStatus;

    @Schema(description = "Drive duration in seconds")
    private Integer driveDurationSeconds;

    @Schema(description = "Drive distance in meters")
    private Integer driveDistanceMeters;

    @Schema(description = "Actual arrival timestamp")
    private LocalDateTime actualArrivalAt;

    @Schema(description = "Dwell time in minutes")
    private Integer dwellMinutes;

    @Schema(description = "Actual stop duration in minutes (T5 - T4)")
    private Integer actualDwellMinutes;

    @Schema(description = "Strict stop completion status: OK if SW<=T5<=EW else KO")
    private String completionStatus;

    @Schema(description = "SLA extra buffer in minutes")
    private Integer bufferMinutes;

    @Schema(description = "Manual start time window")
    private java.time.LocalTime startTimeWindow;

    @Schema(description = "Manual end time window")
    private java.time.LocalTime endTimeWindow;

    // ── Delivery-level fields for driver app inline display ───────────────────
    @Schema(description = "Linked delivery status", example = "IN_TRANSIT")
    private String deliveryStatus;

    @Schema(description = "Linked client name")
    private String clientName;

    @Schema(description = "Linked client phone")
    private String clientPhone;

    @Schema(description = "Linked order total amount")
    private java.math.BigDecimal totalAmount;

    @Schema(description = "Order reference shown to driver")
    private String orderRef;

    // ── Delay tracking for dashboard ──────────────────────────────────────────
    @Schema(description = "Delay in minutes, negative means early")
    private Integer delayMinutes;           // negative = early, positive = late
    @Schema(description = "Human-readable delay reason")
    private String delayReason;             // "On time", "15 min early", "Failed stop (cascading)", etc.
    @Schema(description = "Delay status", example = "LATE")
    private String delayStatus;             // "ON_TIME", "EARLY", "LATE"

    // ── Legacy stop fields (reassigned/replanned) ─────────────────────────────
    @Schema(description = "Timestamp when stop was removed from active route")
    private LocalDateTime removedAt;
    @Schema(description = "Removal reason", example = "REASSIGNED")
    private String removedReason;           // "REASSIGNED" or "REPLANNED"
    @Schema(description = "Actor that removed the stop")
    private String removedBy;
}
