package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ZoneRequest {

    @NotBlank
    private String name;

    private String color;

    private String description;

    private List<String> cities;

    private List<String> postalCodes;

    private Boolean isActive;
}
