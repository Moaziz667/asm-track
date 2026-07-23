package com.asm.driver.security;

import java.util.UUID;

/**
 * Holds the current tenant (company) identifier for the request thread.
 * Set by {@link TenantContextFilter} from the X-Company-Id header injected by the API Gateway.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID companyId) {
        CURRENT.set(companyId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
