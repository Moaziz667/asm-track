package com.asm.delivery.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/** Security principal stored in the SecurityContext after JWT validation. */
@Getter
@AllArgsConstructor
public class UserPrincipal {
    private final String  userId;          // UUID string or any opaque ID
    private final String  role;            // "CLIENT" | "DRIVER" | "ADMIN" | "DISPATCHER"
    private final String  name;            // display name from JWT (may be null)
    private final String  phone;           // phone number from JWT (may be null)
    private final Integer odooPartnerId;   // Odoo res.partner ID (may be null)
}
