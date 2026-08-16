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
    /** Which ERP produced this preview ("ODOO" / "ERPNEXT") — the adapter is the authority. */
    private String source;
    private String customerRef;
    /** ERP values the integrator mapped that have no canonical ASM field; display-only. */
    private java.util.Map<String, Object> customFields;

    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String deliveryCity;
    /** Postal code as the ERP holds it — what ASM resolves the delivery's zone from. */
    private String deliveryPostalCode;
    private String deliveryInstructions;
    private BigDecimal totalAmount;
    private String currency;
    /** Whether the driver must collect payment on arrival (cash on delivery). */
    private Boolean codRequired;
    /** How much to collect when {@link #codRequired}; ignored otherwise. */
    private BigDecimal codAmount;
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

    /**
     * Why each field looks the way it does, keyed by canonical field name.
     *
     * <p>Only filled for the preview, and only for fields that are not a plain readable value. A blank
     * cell otherwise means three different things at once — empty in the ERP, unreadable, or a path
     * pointing at nothing — and the integrator has no way to tell which. Carrying the state alongside
     * the value is what turns the preview from "look at the result" into "look at what went wrong".
     */
    private java.util.Map<String, FieldRead> fieldReads;

    /**
     * One field's reading.
     *
     * @param state  EMPTY when the ERP genuinely holds nothing, UNREADABLE when a value is there but
     *               no honest conversion exists
     * @param reason human explanation, present only for UNREADABLE
     */
    public record FieldRead(String state, String reason) {}
}
