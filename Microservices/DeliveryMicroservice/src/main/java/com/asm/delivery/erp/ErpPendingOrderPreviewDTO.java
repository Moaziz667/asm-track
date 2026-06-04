package com.asm.delivery.erp;

import com.asm.delivery.entity.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Full detail of an ERP order before importing it into ASM Track")
public class ErpPendingOrderPreviewDTO {

    @Schema(description = "ERP order reference", example = "S-00042")
    private String erpOrderId;

    @Schema(description = "Client's own purchase order reference", example = "PO-2026-001")
    private String externalRef;

    @Schema(description = "ERP source system", example = "ODOO")
    private String source;

    @Schema(description = "End client full name", example = "Mohamed Ali Ben Salah")
    private String customerName;

    @Schema(description = "End client phone number", example = "21612345678")
    private String customerPhone;

    @Schema(description = "Full delivery address", example = "12 Rue de la Paix, Cité Olympique")
    private String deliveryAddress;

    @Schema(description = "Delivery city", example = "Tunis")
    private String deliveryCity;

    @Schema(description = "Delivery instructions from the ERP", example = "Call before delivery")
    private String deliveryInstructions;

    @Schema(description = "Order total amount including taxes", example = "150.500")
    private BigDecimal totalAmount;

    @Schema(description = "Currency code (3 chars)", example = "TND")
    private String currency;

    @Schema(description = "Order priority", example = "NORMAL", allowableValues = {"NORMAL", "HIGH", "URGENT"})
    private String priority;

    @Schema(description = "Odoo payment term name", example = "Immediate Payment")
    private String paymentTermName;

    @Schema(description = "Date the order was confirmed in ERP", example = "2026-05-13T09:00:00")
    private LocalDateTime dateOrder;

    @Schema(description = "Requested delivery date/time", example = "2026-05-14T12:00:00")
    private LocalDateTime scheduledAt;

    @Schema(description = "All line items (products) in this order")
    private List<OrderItem> items;

    @Schema(description = "Total number of units across all items", example = "5")
    private Integer totalQuantity;

    @Schema(description = "Total weight of all items in kg", example = "12.500")
    private BigDecimal totalWeightKg;

    @Schema(description = "True if already imported — importing again returns 409", example = "false")
    private boolean alreadyImported;

    @Schema(description = "Existing ASM Track delivery ID if already imported")
    private UUID existingDeliveryId;

    @Schema(description = "Odoo backorder picking ID from a previous partial delivery", example = "42")
    private Integer existingBackorderId;

    // ── Delivery-note (bon de livraison) fields — enterprise multi-depot ──────────

    @Schema(description = "Official ERP delivery-note / picking number (bon de livraison)", example = "WH/OUT/00012")
    private String blNumber;

    @Schema(description = "Source sale-order reference the delivery note was generated from", example = "S00042")
    private String saleOrderRef;

    @Schema(description = "Short code of the source warehouse the goods ship from", example = "SFAX")
    private String warehouseCode;

    @Schema(description = "Human name of the source warehouse", example = "Entrepôt Sfax")
    private String warehouseName;

    @Schema(description = "True when the delivery note is ready to ship (Odoo picking state 'assigned')", example = "true")
    private Boolean ready;
}
