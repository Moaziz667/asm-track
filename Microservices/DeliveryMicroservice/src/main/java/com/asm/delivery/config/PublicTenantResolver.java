package com.asm.delivery.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves which tenant a delivery belongs to, from a bare {@code deliveryId} alone — the chicken-and-egg
 * of public tracking: the request carries no {@code X-Company-Id} (unauthenticated), yet the delivery lives
 * in one tenant's {@code company_<id>.deliveries}.
 *
 * <p>Strategy: scan the {@code company_%} schemas for the id (a globally-unique UUID) with a raw
 * {@link JdbcTemplate} (bypasses Hibernate multi-tenancy, runs on the default connection), and cache the
 * hit. Deliveries never move tenants, so a positive mapping is permanent — subsequent lookups are O(1).
 * Only hits are cached (a miss may just be a not-yet-created delivery), so the cache never poisons.
 *
 * <p>No shared registry table: Keycloak stays the tenant registry, the schema catalog is the data registry.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PublicTenantResolver {

    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, UUID> cache = new ConcurrentHashMap<>();

    /** @return the owning companyId, or {@code null} if no tenant schema contains this delivery. */
    public UUID resolveCompanyId(UUID deliveryId) {
        UUID cached = cache.get(deliveryId);
        if (cached != null) return cached;

        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'company\\_%'",
                String.class);

        for (String schema : schemas) {
            List<Integer> hit = jdbcTemplate.query(
                    "SELECT 1 FROM \"" + schema + "\".deliveries WHERE id = ? LIMIT 1",
                    (rs, rowNum) -> 1,
                    deliveryId);
            if (!hit.isEmpty()) {
                UUID companyId = TenantSchema.companyIdFrom(schema);
                if (companyId != null) {
                    cache.put(deliveryId, companyId);
                    return companyId;
                }
            }
        }
        return null;
    }
}
