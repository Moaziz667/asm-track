package com.asm.delivery.dto.response;

import com.asm.delivery.entity.VehicleStatus;
import com.asm.delivery.entity.VehicleType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class VehicleResponse {
    private UUID id;
    private String name;
    private String make;
    private String model;
    private Integer manufactureYear;
    private String color;
    private String vin;
    private String fuelType;
    private Integer payloadKg;
    private Double volumeM3;
    private Integer mileageKm;
    private String plate;
    private VehicleType type;
    private String imageUrl;
    private UUID driverId;
    private Boolean assigned;
    private Boolean active;
    private VehicleStatus vehicleStatus;
    private LocalDateTime createdAt;
}
