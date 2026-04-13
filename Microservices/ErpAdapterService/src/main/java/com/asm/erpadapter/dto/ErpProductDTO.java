package com.asm.erpadapter.dto;

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
    private double price;
    private int stock;
    private boolean available;
    private String description;
    private double weightKg;
}
