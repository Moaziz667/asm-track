package com.asm.delivery.dto.response;

import com.asm.delivery.entity.VehicleInspection.InspectionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class VehicleInspectionResponse {
    private UUID id;
    private UUID vehicleId;
    private UUID driverId;
    private Integer odometerReading;
    private Integer fuelLevel;
    private InspectionStatus tiresStatus;
    private InspectionStatus brakesStatus;
    private InspectionStatus lightsStatus;
    private String notes;
    private LocalDateTime inspectedAt;
}
