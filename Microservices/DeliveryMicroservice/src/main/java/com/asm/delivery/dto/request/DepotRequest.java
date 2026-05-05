package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DepotRequest {

    @NotBlank
    private String name;

    private String address;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Boolean isActive;
}
