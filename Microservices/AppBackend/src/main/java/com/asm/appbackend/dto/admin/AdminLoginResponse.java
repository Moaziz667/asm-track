package com.asm.appbackend.dto.admin;

import lombok.Builder;
import com.asm.appbackend.dto.admin.AdminUserResponse;

@Builder
public record AdminLoginResponse(
        String token,
        String tokenType,
        long expiresInMs,
        AdminUserResponse user
) {}
