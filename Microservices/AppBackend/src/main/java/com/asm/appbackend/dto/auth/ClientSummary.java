package com.asm.appbackend.dto.auth;

import lombok.Builder;

@Builder
public record ClientSummary(
        String id,
        String name,
        String phone,
        String email
) {
}
