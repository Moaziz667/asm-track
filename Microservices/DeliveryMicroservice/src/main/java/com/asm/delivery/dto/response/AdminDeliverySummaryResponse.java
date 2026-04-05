package com.asm.delivery.dto.response;

import com.asm.delivery.entity.OrderSource;
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
public class AdminDeliverySummaryResponse {
    private UUID deliveryId;
    private UUID orderId;
    private UUID routeId;
    private String routeName;
    private String status;
    private OrderSource source;

    private String clientName;
    private String dropoffAddress;
    private String dropoffCity;
    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;
    private boolean dropoffPinned;

    private UUID driverId;
    private String driverName;
    private String driverPhone;

    private BigDecimal totalAmount;
    private BigDecimal totalWeightKg;
    private BigDecimal routeDistanceKm;
    private Integer routeDurationMinutes;
    private Integer transitSlaMinutesComputed;
    private LocalDateTime routeEtaAt;
    private String routeGeometry;
    private String routeProvider;
    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime inTransitAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime updatedAt;
}
