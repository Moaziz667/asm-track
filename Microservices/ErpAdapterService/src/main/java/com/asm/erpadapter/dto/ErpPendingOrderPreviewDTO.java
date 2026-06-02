package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full preview of a pending ERP order including line items.
 * Used by admin to inspect an order before importing it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPendingOrderPreviewDTO {
    private String erpOrderId;
    private String externalRef;
    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String deliveryCity;
    private String deliveryInstructions;
    private BigDecimal totalAmount;
    private String currency;
    private String paymentTermName; // raw Odoo payment_term_id name e.g. "Immediate Payment"
    private String priority;
    private LocalDateTime dateOrder;
    private LocalDateTime scheduledAt;
    private List<ErpOrderItemDTO> items;
    private Integer totalQuantity;
    private BigDecimal totalWeightKg;

    // ── Delivery-note (bon de livraison) fields — enterprise multi-depot ──────────
    /** Official delivery-note / picking number from the ERP (e.g. Odoo "WH/OUT/00012"). */
    private String blNumber;
    /** Source sale-order reference this delivery note was generated from (e.g. "S00042"). */
    private String saleOrderRef;
    /** Short code of the source warehouse the goods ship from (e.g. "SFAX"). */
    private String warehouseCode;
    /** Human name of the source warehouse (e.g. "Entrepôt Sfax"). */
    private String warehouseName;
    /** True when the delivery note is ready to ship (Odoo picking state = 'assigned'). */
    private Boolean ready;
}
