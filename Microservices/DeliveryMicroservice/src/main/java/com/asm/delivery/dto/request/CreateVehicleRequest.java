package com.asm.delivery.dto.request;

import com.asm.delivery.entity.VehicleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateVehicleRequest {
    @NotBlank
    private String make;

    @NotBlank
    private String model;

    @NotNull
    @Min(1950)
    @Max(2100)
    private Integer manufactureYear;

    private String color;

    private String vin;

    private String fuelType;

    private Integer payloadKg;

    private Double volumeM3;

    private Integer mileageKm;

    @NotBlank
    private String plate;

    @NotNull
    private VehicleType type;

    private String imageBase64;
}
