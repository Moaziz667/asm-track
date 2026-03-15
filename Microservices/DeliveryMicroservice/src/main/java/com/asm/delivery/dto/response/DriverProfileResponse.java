package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverProfileResponse {
    private UUID          id;
    private String        name;
    private String        phone;
    private Boolean       available;
    private BigDecimal    currentLat;
    private BigDecimal    currentLng;
    private LocalDateTime lastLocationAt;
    private LocalDateTime createdAt;
    private String        city; // Added city field
}
