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
    private BigDecimal unitWeightKg;
    /** Canonical product type (vendor-neutral): STORABLE, CONSUMABLE, or SERVICE. */
    private String productType;
}
