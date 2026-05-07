package com.asm.driver.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AdminDriverResponse {
    private String id;
    private String name;
    private String phone;
    private boolean active;
    private BigDecimal currentLat;
    private BigDecimal currentLng;
    private LocalDateTime lastLocationAt;
    private LocalDateTime createdAt;
    private int totalDeliveries;
    private int delivered;
    private int failed;
}
