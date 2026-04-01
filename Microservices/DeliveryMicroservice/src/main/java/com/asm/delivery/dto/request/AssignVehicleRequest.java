package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignVehicleRequest {
    @NotNull
    private UUID driverId;
}
