package com.asm.appbackend.dto.auth;

import lombok.Builder;

@Builder
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInMs,
        ClientSummary client
) {
}
