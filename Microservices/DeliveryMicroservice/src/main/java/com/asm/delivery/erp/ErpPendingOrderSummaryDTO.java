package com.asm.delivery.erp;

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
public class ErpPendingOrderSummaryDTO {
    private String erpOrderId;
    private String externalRef;
    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String deliveryCity;
    private BigDecimal totalAmount;
    private String currency;
    private String state;
    private String invoiceStatus;
    private LocalDateTime dateOrder;
    private LocalDateTime scheduledAt;
    private boolean alreadyImported;
    private UUID existingDeliveryId;
    private Integer existingBackorderId;
}