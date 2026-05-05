package com.asm.delivery.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CodCollectionRequest {
    @NotNull
    private Boolean codCollected;

    // Required only when codCollected = true
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal codAmountCollected;
}
