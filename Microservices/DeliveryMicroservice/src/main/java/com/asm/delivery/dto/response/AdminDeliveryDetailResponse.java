package com.asm.delivery.dto.response;

import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderSource;
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
public class AdminDeliveryDetailResponse {
    private UUID deliveryId;
    private UUID orderId;
    private String status;
    private FailureCode failureCode;
    private String failureComment;

    private UUID driverId;
    private String driverName;
    private String driverPhone;

    private OrderSource source;
    private String erpOrderId;
    private String clientName;
    private String clientPhone;
    private String clientEmail;
    private String dropoffAddress;
    private String dropoffCity;
    private String dropoffPostalCode;
    private String dropoffCountryCode;
    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;
    private boolean dropoffPinned;
    private String deliveryInstructions;

    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private BigDecimal totalWeightKg;
    private BigDecimal routeDistanceKm;
    private Integer routeDurationMinutes;
    private Integer transitSlaMinutesComputed;
    private LocalDateTime routeEtaAt;
    private String routeGeometry;
    private String routeProvider;
    private String currency;
    private String odooSyncStatus;
    private Integer odooBackorderId;

    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime inTransitAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelledAt;

    private boolean podExists;
    private List<StatusHistoryResponse> statusHistory;
}
