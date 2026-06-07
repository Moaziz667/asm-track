package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single returned line for an RMA reverse stock move. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpReturnItemDTO {
    private String sku;
    private String name;
    private Integer quantity;
    private String condition; // RESELLABLE | DAMAGED
}
