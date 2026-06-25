package com.asm.driver.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal implements java.security.Principal {
    private String userId;
    private String role;
    /** The token's {@code name} claim (Keycloak-mastered display name) — used to refresh the mirror. */
    private String displayName;

    @Override
    public String getName() {
        return userId;
    }
}
