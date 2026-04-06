package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ZoneResponse {
    private UUID id;
    private String name;
    private String color;
    private String description;
    private List<String> cities;
    private List<String> postalCodes;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
