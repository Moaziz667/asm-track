package com.asm.delivery.erp;

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
public class ErpPendingOrderPreviewDTO {
    private String erpOrderId;
    private String externalRef;
    private String source;
    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String deliveryCity;
    private String deliveryInstructions;
    private BigDecimal totalAmount;
    private String currency;
    private String priority;
    private LocalDateTime dateOrder;
    private LocalDateTime scheduledAt;
    private List<OrderItem> items;
    private Integer totalQuantity;
    private boolean alreadyImported;
    private UUID existingDeliveryId;
    private Integer existingBackorderId;
}