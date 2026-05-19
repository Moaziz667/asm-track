package com.asm.delivery.dto.request;

import com.asm.delivery.entity.VehicleStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VehicleStatusRequest {
    @NotNull
    private VehicleStatus status;
}
