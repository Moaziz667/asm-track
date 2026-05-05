package com.asm.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Assign a waiting delivery to a specific driver")
public class AssignDeliveryRequest {
    @NotNull
    @Schema(description = "Target driver unique identifier", example = "a1f5d102-9e5e-4b66-a605-2be9fb880ce1")
    private UUID driverId;
}
