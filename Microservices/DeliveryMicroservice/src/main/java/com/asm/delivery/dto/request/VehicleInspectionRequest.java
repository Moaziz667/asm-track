package com.asm.delivery.dto.request;

import com.asm.delivery.entity.VehicleInspection.InspectionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class VehicleInspectionRequest {
    @NotNull
    private UUID vehicleId;

    private Integer odometerReading;

    @Min(0) @Max(100)
    private Integer fuelLevel;

    @NotNull
    private InspectionStatus tiresStatus;

    @NotNull
    private InspectionStatus brakesStatus;

    @NotNull
    private InspectionStatus lightsStatus;

    private String notes;
}
