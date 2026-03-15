package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FailDeliveryRequest {
    @NotBlank
    private String reason;
}
