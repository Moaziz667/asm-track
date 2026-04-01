package com.asm.delivery.erp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpProductDTO {
    private String erpProductId;
    private String name;
    private String sku;
    private Double price;
    private Integer stock;
    private Boolean available;
    private String description;
    private Double weightKg;
}
