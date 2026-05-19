package com.asm.driver.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal {
    private String userId;
    private String role;
    private String companyId; // null = ASM super-admin
}
