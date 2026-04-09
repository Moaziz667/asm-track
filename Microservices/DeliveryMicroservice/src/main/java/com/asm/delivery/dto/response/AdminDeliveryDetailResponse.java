package com.asm.delivery.dto.response;

import com.asm.delivery.entity.FailureCode;
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
@Schema(description = "Detailed delivery contract used by admin details screen")
public class AdminDeliveryDetailResponse {
    @Schema(description = "Delivery unique identifier")
    private UUID deliveryId;

    @Schema(description = "Order unique identifier")
    private UUID orderId;

    @Schema(description = "Delivery status", example = "PICKED_UP")
    private String status;

    @Schema(description = "Failure classification when delivery failed")
    private FailureCode failureCode;

    @Schema(description = "Failure comment/details")
    private String failureComment;

    @Schema(description = "Assigned driver id")
    private UUID driverId;

    @Schema(description = "Assigned driver name")
    private String driverName;

    @Schema(description = "Assigned driver phone")
    private String driverPhone;

    @Schema(description = "Order source", example = "APP")
    private OrderSource source;

    @Schema(description = "External ERP order id")
    private String erpOrderId;

    @Schema(description = "Client full name")
    private String clientName;

    @Schema(description = "Client phone")
    private String clientPhone;

    @Schema(description = "Client email")
    private String clientEmail;

    @Schema(description = "Dropoff address")
    private String dropoffAddress;

    @Schema(description = "Dropoff city")
    private String dropoffCity;

    @Schema(description = "Dropoff postal code")
    private String dropoffPostalCode;

    @Schema(description = "Dropoff country code", example = "TN")
    private String dropoffCountryCode;

    @Schema(description = "Dropoff latitude")
    private BigDecimal dropoffLat;

    @Schema(description = "Dropoff longitude")
    private BigDecimal dropoffLng;

    @Schema(description = "True if dropoff coordinates are pinned")
    private boolean dropoffPinned;

    @Schema(description = "Resolved zone id")
    private UUID zoneId;

    @Schema(description = "Resolved zone name")
    private String zoneName;

    @Schema(description = "Resolved zone color")
    private String zoneColor;

    @Schema(description = "Delivery instructions")
    private String deliveryInstructions;

    @Schema(description = "Order items snapshot")
    private List<OrderItem> items;

    @Schema(description = "Order total amount")
    private BigDecimal totalAmount;

    @Schema(description = "Order total weight in kg")
    private BigDecimal totalWeightKg;

    @Schema(description = "Route distance in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Route duration in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "Computed transit SLA in minutes")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Route ETA")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Routing provider", example = "OSRM")
    private String routeProvider;

    @Schema(description = "ISO currency code", example = "TND")
    private String currency;

    @Schema(description = "ERP sync status")
    private String odooSyncStatus;

    @Schema(description = "Backorder id in ERP if generated")
    private Integer odooBackorderId;

    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime inTransitAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelledAt;

    @Schema(description = "True when POD exists for this delivery")
    private boolean podExists;

    @Schema(description = "Status timeline for audit/tracking")
    private List<StatusHistoryResponse> statusHistory;
}
