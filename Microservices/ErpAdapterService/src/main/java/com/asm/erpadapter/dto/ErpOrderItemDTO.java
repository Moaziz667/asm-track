package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpOrderItemDTO {
    private String name;
    private String sku;
    private Integer quantity;
    private BigDecimal unitPrice;
    /**
     * Unit price taxes included — what a customer actually owes for one of these.
     *
     * <p>{@link #unitPrice} is the untaxed figure the ERP displays on the line, and anything summed
     * from it is short by the VAT. That difference only matters in one place, and it is the place
     * where being wrong is worst: the amount a driver asks for at the door.
     *
     * <p>Null when the ERP gives no way to derive it. Callers must then decline to state an amount
     * rather than fall back on the untaxed one.
     */
    private BigDecimal unitPriceTtc;
    private BigDecimal unitWeightKg;
    /** Canonical product type (vendor-neutral): STORABLE, CONSUMABLE, or SERVICE. */
    private String productType;

    /**
     * Warehouse this line ships from. Equal to the note's warehouse in ERPs that hold one per
     * document; distinct when the ERP allows a line to draw from elsewhere.
     */
    private String warehouseCode;
}
