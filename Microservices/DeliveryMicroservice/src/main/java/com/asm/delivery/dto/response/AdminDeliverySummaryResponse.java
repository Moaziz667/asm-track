package com.asm.delivery.dto.response;

import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

    @Schema(description = "Human readable order reference (ERP ID or short ID)")
    private String orderRef;

    @Schema(description = "Odoo/ERP order identifier (e.g. S00123)")
    private String erpOrderId;

    @Schema(description = "Human-readable ERP reference — used for backorders where erpOrderId is null (e.g. S00123/BO)")
    private String erpExternalRef;

    @Schema(description = "Assigned route id when available")
    private UUID routeId;

    @Schema(description = "Assigned route name")
    private String routeName;

    @Schema(description = "Assigned route status")
    private String routeStatus;

    @Schema(description = "Delivery status", example = "IN_TRANSIT")
    private String status;

    @Schema(description = "FORWARD (depot→client) or RETURN_PICKUP (client→depot, a return collection). ADR-033")
    private String kind;

    /** Canonical failure category (FailureCode name) when the delivery failed. */
    private String failureCode;
    /** Human reason (catalog label enriched with the driver comment) when failed. */
    private String failReason;

    @Schema(description = "Order source", example = "ODOO")
    private OrderSource source;

    @Schema(description = "ERP source-warehouse code from the delivery note (bon de livraison)")
    private String warehouseCode;

    @Schema(description = "Resolved source depot id (null when the warehouse has no synced depot)")
    private UUID sourceDepotId;

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

    @Schema(description = "ISO currency code", example = "TND")
    private String currency;

    @Schema(description = "Order total weight in kilograms")
    private BigDecimal totalWeightKg;

    @Schema(description = "Route distance in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Route duration in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "Computed transit SLA in minutes")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Unified SLA phase (source of truth): PLANNING/ASSIGNMENT/DEPARTURE/DELIVERY/HANDOFF/DELIVERED/PARTIAL/FAILED/CANCELLED")
    private String slaPhase;

    @Schema(description = "Unified SLA health: ON_TRACK/AT_RISK/BREACHED/MET/LATE/NONE")
    private String slaHealth;

    @Schema(description = "Worst SLA health any lifecycle phase ever reached (from persisted phaseHealth). "
            + "For a FAILED/CANCELLED delivery whose live health is NONE, this still reveals it was BREACHED/LATE "
            + "before it failed — so historical lateness isn't lost.")
    private String slaWorstHealth;

    @Schema(description = "How late the delivery was, in minutes (persisted), if any phase ran over its deadline.")
    private Integer slaLateMinutes;

    @Schema(description = "Estimated time of arrival for route leg")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Routing provider", example = "OSRM")
    private String routeProvider;

    @Schema(description = "Effective scheduled date/time (replan date if rescheduled, else ERP date)")
    private LocalDateTime scheduledAt;

    @Schema(description = "Replan date set by admin; non-null when the delivery was rescheduled")
    private LocalDateTime rescheduledAt;

    @Schema(description = "Delivery creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Assignment timestamp")
    private LocalDateTime assignedAt;

    @Schema(description = "Pickup timestamp (parcel loaded at depot)")
    private LocalDateTime pickedUpAt;

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

    @Schema(description = "Route start timestamp (actual)")
    private LocalDateTime routeStartedAt;

    @Schema(description = "Route departure timestamp (actual)")
    private LocalDateTime routeDepartureTime;

    @Schema(description = "Route planned date")
    private java.time.LocalDate routeDate;

    @Schema(description = "Route planned start time")
    private java.time.LocalTime routePlannedStartTime;

    @Schema(description = "Route stop end time window")
    private java.time.LocalTime routeEndTimeWindow;

    @Schema(description = "Committed delivery window start (HH:mm) — the route stop's start window, mirrors the detail endpoint")
    private String timeSlotStartTime;

    @Schema(description = "Committed delivery window end (HH:mm) — the route stop's end window, mirrors the detail endpoint")
    private String timeSlotEndTime;

    @Schema(description = "Total number of items in the order")
    private Integer totalQuantity;

    @Schema(description = "Comma-separated summary of items (e.g. '3x Item A, 1x Item B')")
    private String itemsSummary;

    @Schema(description = "Detailed list of items in the order")
    private List<OrderItem> items;
}
