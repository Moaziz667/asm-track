package com.asm.appbackend.security;

import java.util.UUID;

public record UserPrincipal(
        String userId,
        String role,
        String name,
        UUID companyId
) implements java.security.Principal {

    @Override
    public String getName() {
        return userId;
    }
}
