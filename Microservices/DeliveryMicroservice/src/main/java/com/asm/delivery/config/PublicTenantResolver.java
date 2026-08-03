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

    /**
     * Bounded positive cache (LRU): the endpoint is UNAUTHENTICATED, so an unbounded map keyed by
     * caller-supplied UUIDs is a memory-growth vector; and each miss costs one query PER tenant
     * schema, so unknown ids are also negative-cached briefly (a delivery that doesn't exist yet
     * will be found on the next window). Access-ordered LinkedHashMap under its own lock — the scan
     * itself stays outside the lock.
     */
    private static final int MAX_POSITIVE_ENTRIES = 50_000;
    private static final long NEGATIVE_TTL_MS = 60_000;

    private final Map<UUID, UUID> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, UUID> eldest) {
                    return size() > MAX_POSITIVE_ENTRIES;
                }
            });
    private final Map<UUID, Long> negativeCache = new ConcurrentHashMap<>();

    /** @return the owning companyId, or {@code null} if no tenant schema contains this delivery. */
    public UUID resolveCompanyId(UUID deliveryId) {
        UUID cached = cache.get(deliveryId);
        if (cached != null) return cached;

        Long missAt = negativeCache.get(deliveryId);
        if (missAt != null) {
            if (System.currentTimeMillis() - missAt < NEGATIVE_TTL_MS) return null;
            negativeCache.remove(deliveryId);
        }

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
        negativeCache.put(deliveryId, System.currentTimeMillis());
        if (negativeCache.size() > MAX_POSITIVE_ENTRIES) {
            long cutoff = System.currentTimeMillis() - NEGATIVE_TTL_MS;
            negativeCache.values().removeIf(t -> t < cutoff);
        }
        return null;
    }
}
