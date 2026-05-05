package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HandoffTokenResponse {
    private String token;
    private String deliveryId;
    private LocalDateTime expiresAt;
}
