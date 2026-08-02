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
    private String customerRef;

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

    @Schema(description = "Whether the driver must collect payment on arrival", example = "false")
    private Boolean codRequired;

    @Schema(description = "Amount to collect when codRequired", example = "150.500")
    private BigDecimal codAmount;

    @Schema(description = "Date the order was confirmed in the ERP", example = "2026-05-13T09:00:00")
    private LocalDateTime dateOrder;

    @Schema(description = "Requested delivery date/time from the ERP", example = "2026-05-14T12:00:00")
    private LocalDateTime scheduledAt;

    @Schema(description = "True if this order has already been imported into ASM Track", example = "false")
    private boolean alreadyImported;

    @Schema(description = "ID of the existing ASM Track delivery if already imported", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID existingDeliveryId;

    // ── Delivery-note (bon de livraison) fields — enterprise multi-depot ──────────

    @Schema(description = "Official ERP delivery-note / picking number", example = "WH/OUT/00012")
    private String blNumber;

    @Schema(description = "Source sale-order reference", example = "S00042")
    private String saleOrderRef;

    @Schema(description = "Short code of the source warehouse", example = "SFAX")
    private String warehouseCode;

    @Schema(description = "Human name of the source warehouse", example = "Entrepôt Sfax")
    private String warehouseName;

    @Schema(description = "True when ready to ship", example = "true")
    private Boolean ready;

    @Schema(description = "True when this picking is a backorder (reliquat of a prior partial delivery)", example = "true")
    /** NORMAL | HIGH — so the list can flag an urgent order before anyone imports it. */
    private String priority;

    private boolean backorder;

    @Schema(description = "BL number of the origin picking this is a backorder of", example = "WH/OUT/00012")
    private String originBl;
}
