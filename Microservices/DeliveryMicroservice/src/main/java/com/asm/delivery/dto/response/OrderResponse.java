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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private UUID          id;
    private String        source;
    private String        clientId;
    private String        clientName;
    private String        clientPhone;
    private String        dropoffAddress;
    private String        dropoffCity;
    private BigDecimal    dropoffLat;
    private BigDecimal    dropoffLng;
    private String        deliveryInstructions;
    private BigDecimal    totalAmount;
    private String        currency;
    private String        priority;
    private LocalDateTime scheduledAt;
    private List<OrderItem> items;
    private Integer       totalQuantity;
    private BigDecimal    totalWeightKg;
    private String        status;           // order status
    private UUID          deliveryId;
    private String        deliveryStatus;   // delivery status
    private String        erpOrderId;
    private String        erpExternalRef;
    private String        erpSyncStatus;
    private Integer       erpBackorderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
