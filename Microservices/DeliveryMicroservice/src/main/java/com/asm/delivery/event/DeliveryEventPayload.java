package com.asm.delivery.event;

import com.asm.delivery.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryEventPayload {
    private String deliveryId;
    private String erpOrderId;
    private String status;
    
    // Driver info
    private String driverId;
    private String driverName;
    private String previousDriverId;
    private String newDriverId;
    
    // Client & Address
    private String clientName;
    private String clientPhone;
    private String dropoffAddress;
    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;
    
    // Financial & Items
    private BigDecimal totalAmount;
    private String currency;
    private Boolean isCod;
    private List<OrderItem> items;
    
    // Route tracking
    private String etaAt;
    private BigDecimal routeDistanceKm;
    private Integer routeDurationMinutes;
    private Integer transitSlaMinutes;
    private String routeProvider;
    private BigDecimal lat;
    private BigDecimal lng;
    
    // SLA / Failure
    private String motif;
    private String severity;
    private String reason;
    private String slaMessage;
    private java.util.Map<String, Object> slaParams;
}
