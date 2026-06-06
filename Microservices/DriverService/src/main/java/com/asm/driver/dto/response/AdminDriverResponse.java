package com.asm.driver.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class AdminDriverResponse {
    private String id;
    private String name;
    private String phone;
    private boolean isRegistered;
    private String accountStatus;
    private BigDecimal currentLat;
    private BigDecimal currentLng;
    private LocalDateTime lastLocationAt;
    private LocalDateTime createdAt;
    private int totalDeliveries;
    private int delivered;
    private int failed;
    private String onlineStatus;
    private String email;
    private String activeDeliveryId;
    private String activeRouteId;
    private String suspendedReason;
    private LocalDateTime invitationExpiresAt;
    private LocalDateTime lastInvitedAt;
    private String invitedByName;
}
