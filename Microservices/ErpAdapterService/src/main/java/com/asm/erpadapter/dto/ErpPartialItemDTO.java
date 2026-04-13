package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPartialItemDTO {
    private String referenceKey;
    private Integer quantityDone;
}
