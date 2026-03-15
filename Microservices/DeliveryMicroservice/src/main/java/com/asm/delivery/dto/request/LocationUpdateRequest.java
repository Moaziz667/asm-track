package com.asm.delivery.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LocationUpdateRequest {
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
    private BigDecimal lat;

    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
    private BigDecimal lng;
}
