package com.asm.delivery.erp;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Summary of an ERP order pending import into ASM Track")
public class ErpPendingOrderSummaryDTO {

    @Schema(description = "ERP order reference (e.g. Odoo sale order name)", example = "S-00042")
    private String erpOrderId;

    @Schema(description = "Client's own purchase order reference, if provided", example = "PO-2026-001")
    private String externalRef;

    @Schema(description = "End client full name", example = "Mohamed Ali Ben Salah")
    private String customerName;

    @Schema(description = "End client phone number", example = "21612345678")
    private String customerPhone;

    @Schema(description = "Delivery address as a single string", example = "12 Rue de la Paix, Cité Olympique")
    private String deliveryAddress;

    @Schema(description = "Delivery city", example = "Tunis")
    private String deliveryCity;

    @Schema(description = "Order total amount", example = "150.500")
    private BigDecimal totalAmount;

    @Schema(description = "Currency code", example = "TND")
    private String currency;

    @Schema(description = "Odoo sale order state", example = "sale", allowableValues = {"draft", "sent", "sale", "done", "cancel"})
    private String state;

    @Schema(description = "Odoo invoice status", example = "invoiced", allowableValues = {"upselling", "invoiced", "to invoice", "nothing"})
    private String invoiceStatus;

    @Schema(description = "Date the order was confirmed in the ERP", example = "2026-05-13T09:00:00")
    private LocalDateTime dateOrder;

    @Schema(description = "Requested delivery date/time from the ERP", example = "2026-05-14T12:00:00")
    private LocalDateTime scheduledAt;

    @Schema(description = "True if this order has already been imported into ASM Track", example = "false")
    private boolean alreadyImported;

    @Schema(description = "ID of the existing ASM Track delivery if already imported", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID existingDeliveryId;

    @Schema(description = "Odoo backorder picking ID if a partial delivery was previously done", example = "42")
    private Integer existingBackorderId;
}
