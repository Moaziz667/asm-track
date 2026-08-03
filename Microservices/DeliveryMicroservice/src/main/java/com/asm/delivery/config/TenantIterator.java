package com.asm.delivery.config;

import com.asm.delivery.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Iterates over every provisioned tenant and runs a job for each. Used by {@code @Scheduled} jobs
 * (e.g. the outbox processor) that must process data per-tenant, since scheduled work doesn't go
 * through the web request chain that normally sets the {@link TenantContext}.
 *
 * <p>Source of tenants = the <b>schema catalog</b> ({@code company_<32hex>} schemas), not a
 * {@code companies} table. This is the operational truth: exactly the tenants that have a provisioned
 * schema to process (a company created in Keycloak but not yet provisioned has no schema, so nothing
 * to iterate). It also avoids depending on a shared registry table (KC Organizations is the identity
 * registry; the catalog is the data registry — see MULTITENANT_PLAN.md §2.5).
 *
 * <p>Only {@code TenantContext.set(companyId)} is needed: the Hibernate connection provider sets the
 * {@code search_path} on whatever connection each JPA query actually borrows. No manual SET search_path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantIterator {

    private final JdbcTemplate jdbcTemplate;

    /** Executes the action once per provisioned tenant, with its TenantContext set then cleared. */
    public void forEachActive(Consumer<UUID> action) {
        List<UUID> companyIds = listProvisionedTenants();
        for (UUID companyId : companyIds) {
            TenantContext.set(companyId);
            org.slf4j.MDC.put("companyId", companyId.toString());
            try {
                action.accept(companyId);
            } catch (Exception e) {
                log.error("Job failed for company {}: {}", companyId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
                org.slf4j.MDC.remove("companyId");
            }
        }
    }

    /** Reads the {@code company_<32hex>} schemas from the catalog and maps each back to a company UUID. */
    private List<UUID> listProvisionedTenants() {
        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'company\\_%'",
                String.class);
        return schemas.stream()
                .map(TenantIterator::schemaToCompanyId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** {@code company_<32 hex>} → UUID (inverse of {@link TenantSchema#schemaFor}). */
    private static UUID schemaToCompanyId(String schemaName) {
        String hex = schemaName.substring("company_".length());
        if (hex.length() != 32) return null;
        try {
            String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
