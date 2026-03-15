package com.asm.appbackend.dto.profile;

import lombok.Builder;

@Builder
public record ProfileResponse(
        String id,
        String name,
        String phone,
        String email,
        String address,
        boolean phoneVerified
) {
}
