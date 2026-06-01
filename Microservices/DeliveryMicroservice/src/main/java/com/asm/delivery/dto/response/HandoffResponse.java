package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HandoffResponse {
    private String id;
    private String state;
    private String deliveryId;
    private String routeId;
    private String erpOrderId;
    private String clientName;
    private String dropoffAddress;
    private String fromDriverId;
    private String fromDriverName;
    private String toDriverId;
    private String toDriverName;
    private LocalDateTime requestedAt;
    private LocalDateTime tokenExpiresAt;
    private LocalDateTime confirmedAt;
    private String reason;
}
