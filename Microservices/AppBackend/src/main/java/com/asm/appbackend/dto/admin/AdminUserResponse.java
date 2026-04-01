package com.asm.appbackend.dto.admin;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdminUserResponse(
        String id,
        String name,
        String email,
        String role,
        boolean active,
        LocalDateTime createdAt
) {}
