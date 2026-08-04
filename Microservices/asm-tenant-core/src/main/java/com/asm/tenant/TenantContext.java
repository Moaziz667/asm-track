package com.asm.tenant;

import java.util.UUID;

/**
 * Holds the current tenant (company) identifier for the request thread.
 *
 * <p>Set from the {@code X-Company-Id} header by the web filter, from the AMQP header by the inbound
 * message post-processor, or explicitly by scheduled jobs iterating tenants. Read by the Hibernate
 * tenant resolver to choose a schema.
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
