package com.asm.delivery.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Security principal stored in the SecurityContext after JWT validation. */
@Getter
@AllArgsConstructor
public class UserPrincipal implements java.security.Principal {
    private final String userId;
    private final String role;
    private final String displayName;
    private final String phone;

    @Override
    public String getName() {
        return userId;
    }
}
