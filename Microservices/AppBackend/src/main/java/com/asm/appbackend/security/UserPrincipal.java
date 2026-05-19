package com.asm.appbackend.security;

public record UserPrincipal(
        String userId,
        String role,
        String name,
        String companyId
) {
}
