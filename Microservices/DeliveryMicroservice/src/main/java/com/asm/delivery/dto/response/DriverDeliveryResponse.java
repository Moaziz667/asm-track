package com.asm.delivery.dto.response;

import com.asm.delivery.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Delivery view exposed to the driver — includes order details, hides client identity. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDeliveryResponse {
    private UUID          deliveryId;
    private UUID          orderId;
    private String        orderRef;       // human-readable: erpOrderId or short UUID
    private String        status;

    // client info (driver needs to confirm identity)
    private String        clientName;
    private String        clientPhone;

    // destination info (driver needs this)
    private String        dropoffAddress;
    private String        dropoffCity;
    private BigDecimal    dropoffLat;
    private BigDecimal    dropoffLng;
    private String        deliveryInstructions;

    // financial info
    private BigDecimal    totalAmount;
    private String        currency;

    // items
    private List<OrderItem> items;
    private Integer         totalQuantity;

    // scheduling
    private String        priority;
    private LocalDateTime scheduledAt;

    // timestamps
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime inTransitAt;
    private String        routeGeometry;
    private BigDecimal    routeDistanceKm;
    private Integer       routeDurationMinutes;
    private Integer       transitSlaMinutesComputed;
    private LocalDateTime routeEtaAt;
    private String        routeProvider;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelledAt;
    private String        failReason;
    private String        cancelReason;
    private LocalDateTime createdAt;

    // handoff fields (populated from RouteStop when requiresHandoff = true)
    private boolean       requiresHandoff;
    private LocalDateTime handoffConfirmedAt;
    private String        handoffToDriverId;
    private String        handoffFromDriverId;
}
