package com.asm.driver.config;

import java.util.UUID;

/**
 * Single source of truth for deriving a PostgreSQL schema name from a company (tenant) id.
 *
 * <p>The UUID's hyphens are stripped so the result is a valid <em>unquoted</em> SQL identifier
 * ({@code company_} + 32 hex chars). Using this everywhere (connection provider, provisioner)
 * guarantees the schema a request routes to is exactly the schema that was provisioned.
 *
 * <p>Because the input is always a parsed {@link UUID} (never raw user input) and the output alphabet
 * is restricted to {@code [a-f0-9_]}, the derived name is injection-safe by construction.
 */
public final class TenantSchema {

    /** Schema used when no tenant is resolved (public endpoints, bootstrap, legacy single-tenant data). */
    public static final String DEFAULT = "public";

    private TenantSchema() {}

    public static String schemaFor(UUID companyId) {
        return "company_" + companyId.toString().replace("-", "");
    }
}
