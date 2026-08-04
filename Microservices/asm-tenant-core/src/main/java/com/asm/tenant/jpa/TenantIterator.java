package com.asm.tenant.jpa;

import com.asm.tenant.TenantContext;
import com.asm.tenant.TenantSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs a job once per provisioned tenant.
 *
 * <p>Scheduled work — the outbox processor, SLA sweeps — never goes through the web request chain,
 * so nothing sets {@link TenantContext} for it. This does, one tenant at a time.
 *
 * <h2>Where the list of tenants comes from</h2>
 * The <b>schema catalog</b>, not a {@code companies} table. That is the operational truth: exactly
 * the tenants that have a schema to process. A company created in the identity provider but not yet
 * provisioned has no schema, therefore nothing to iterate — and no risk of a job failing on a tenant
 * whose tables do not exist yet. It also avoids depending on a registry table that each service would
 * have to replicate.
 *
 * <p>Setting the context is enough: the connection provider applies the {@code search_path} to
 * whatever connection each query actually borrows. No manual {@code SET search_path} here.
 *
 * <p>A failing tenant is logged and skipped rather than aborting the run — one broken tenant must not
 * stop the other six from being processed.
 */
@Slf4j
public class TenantIterator {

    private final JdbcTemplate jdbcTemplate;

    public TenantIterator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Executes the action once per provisioned tenant, with its TenantContext set then cleared. */
    public void forEachActive(Consumer<UUID> action) {
        for (UUID companyId : listProvisioned()) {
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

    /**
     * The company id of every tenant that has a provisioned schema.
     *
     * <p>Public because services without a database of their own — the ERP change poller, for one —
     * need the list to run work per tenant, and ask for it over HTTP. It was added to a single copy
     * of this class before the module existed, which is how a capability one service needed stayed
     * invisible to the other three.
     */
    public List<UUID> listProvisioned() {
        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'company\\_%'",
                String.class);
        return schemas.stream()
                .map(TenantSchema::companyIdFrom)
                .filter(Objects::nonNull)
                .toList();
    }
}
