package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStopStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class RouteStopResponse {
    private UUID id;
    private UUID deliveryId;
    private Integer stopOrder;
    private RouteStopStatus status;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String notes;
    private String deliveryAddress;
    private String deliveryCity;
    private String deliveryPostalCode;
    private String deliveryCountryCode;
    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;
    private boolean dropoffPinned;
}
