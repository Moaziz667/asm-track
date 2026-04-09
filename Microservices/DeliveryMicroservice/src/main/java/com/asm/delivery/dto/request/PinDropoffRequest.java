package com.asm.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Manual pin payload for delivery dropoff geolocation")
public class PinDropoffRequest {

    @NotNull
    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    @Schema(description = "Latitude in WGS84", example = "36.8065")
    private BigDecimal lat;

    @NotNull
    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    @Schema(description = "Longitude in WGS84", example = "10.1815")
    private BigDecimal lng;

    @Schema(description = "Normalized delivery address")
    private String dropoffAddress;

    @Schema(description = "Delivery city", example = "Tunis")
    private String dropoffCity;

    @Schema(description = "Postal code", example = "1000")
    private String dropoffPostalCode;

    @Schema(description = "ISO country code", example = "TN")
    private String dropoffCountryCode;
}