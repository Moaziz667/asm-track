package com.asm.driver.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal implements java.security.Principal {
    private String userId;
    private String role;

    @Override
    public String getName() {
        return userId;
    }
}
