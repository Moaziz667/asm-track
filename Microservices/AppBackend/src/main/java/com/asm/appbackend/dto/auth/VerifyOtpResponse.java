package com.asm.appbackend.dto.auth;

import lombok.Builder;

@Builder
public record VerifyOtpResponse(
        String clientId,
        String message
) {
}
