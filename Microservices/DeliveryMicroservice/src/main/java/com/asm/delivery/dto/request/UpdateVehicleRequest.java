package com.asm.delivery.dto.request;

import com.asm.delivery.entity.VehicleType;
import lombok.Data;

@Data
public class UpdateVehicleRequest {
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
    private Boolean active;
    private String imageBase64;
}
