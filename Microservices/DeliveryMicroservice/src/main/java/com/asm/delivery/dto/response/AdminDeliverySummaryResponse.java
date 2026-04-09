package com.asm.delivery.dto.response;

import com.asm.delivery.entity.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Compact delivery row used by admin tables and kanban lanes")
public class AdminDeliverySummaryResponse {
    @Schema(description = "Delivery unique identifier")
    private UUID deliveryId;

    @Schema(description = "Order unique identifier")
    private UUID orderId;

    @Schema(description = "Assigned route id when available")
    private UUID routeId;

    @Schema(description = "Assigned route name")
    private String routeName;

    @Schema(description = "Delivery status", example = "IN_TRANSIT")
    private String status;

    @Schema(description = "Order source", example = "ODOO")
    private OrderSource source;

    @Schema(description = "Client full name")
    private String clientName;

    @Schema(description = "Dropoff street address")
    private String dropoffAddress;

    @Schema(description = "Dropoff city")
    private String dropoffCity;

    @Schema(description = "Dropoff postal code")
    private String dropoffPostalCode;

    @Schema(description = "Dropoff latitude")
    private BigDecimal dropoffLat;

    @Schema(description = "Dropoff longitude")
    private BigDecimal dropoffLng;

    @Schema(description = "True when dropoff coordinates were manually/geocoded pinned")
    private boolean dropoffPinned;

    @Schema(description = "Resolved zone id")
    private UUID zoneId;

    @Schema(description = "Resolved zone label")
    private String zoneName;

    @Schema(description = "Zone display color")
    private String zoneColor;

    @Schema(description = "Assigned driver id")
    private UUID driverId;

    @Schema(description = "Assigned driver full name")
    private String driverName;

    @Schema(description = "Assigned driver phone")
    private String driverPhone;

    @Schema(description = "Order total amount")
    private BigDecimal totalAmount;

    @Schema(description = "Order total weight in kilograms")
    private BigDecimal totalWeightKg;

    @Schema(description = "Route distance in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Route duration in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "Computed transit SLA in minutes")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Estimated time of arrival for route leg")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Routing provider", example = "OSRM")
    private String routeProvider;

    @Schema(description = "Delivery creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Assignment timestamp")
    private LocalDateTime assignedAt;

    @Schema(description = "Transit start timestamp")
    private LocalDateTime inTransitAt;

    @Schema(description = "Completion timestamp")
    private LocalDateTime completedAt;

    @Schema(description = "Failure timestamp")
    private LocalDateTime failedAt;

    @Schema(description = "Cancellation timestamp")
    private LocalDateTime cancelledAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;
}
