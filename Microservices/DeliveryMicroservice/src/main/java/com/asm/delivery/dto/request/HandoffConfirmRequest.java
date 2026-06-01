package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class HandoffConfirmRequest {
    @NotBlank
    private String token;

    // Optional confirmation evidence captured at the exchange point.
    private BigDecimal lat;
    private BigDecimal lng;
    private String notes;
}
