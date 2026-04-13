package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Summary of a pending ERP order (list view).
 * Does not include line items — use preview endpoint for full details.
 */
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
}
