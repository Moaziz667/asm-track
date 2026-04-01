package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignDeliveryRequest {
    @NotNull
    private UUID driverId;
}
