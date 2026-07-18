package com.asm.delivery.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/** Security principal stored in the SecurityContext after JWT validation. */
@Getter
@AllArgsConstructor
public class UserPrincipal implements java.security.Principal {
    private final String userId;
    private final String role;
    private final String displayName;
    private final String phone;
    private final UUID companyId;

    @Override
    public String getName() {
        return userId;
    }
}
