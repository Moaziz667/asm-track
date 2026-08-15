package com.asm.assistant.security;

import java.util.UUID;

/** The authenticated caller, mirrored from the JWT — same shape as the other ASM services. */
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
