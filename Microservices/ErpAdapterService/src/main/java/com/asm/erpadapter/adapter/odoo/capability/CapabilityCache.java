package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches resolved capability results (field names or method names) per tenant.
 *
 * <p>Sits in front of {@link FieldResolver} and {@link MethodResolver} to avoid
 * repeated resolution for the same capability on the same tenant.
 *
 * <p>Three separate caches are maintained (by design, not mixed):
 * <ul>
 *   <li>{@link OdooMetadataCache} — raw Odoo field metadata per model</li>
 *   <li>{@link CapabilityCache} — resolved capability → field/method name per tenant</li>
 *   <li>Customer mapping (DB) — tenant-specific overrides</li>
 * </ul>
 */
@Component
@Slf4j
public class CapabilityCache {

    private record CacheEntry(String resolvedName, long atMs) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 5 * 60 * 1000L; // 5 min
    private static final String NO_TENANT = "__no_tenant__";

    /**
     * Get a cached resolution for a capability.
     * @return the resolved field/method name, or null if not cached.
     */
    public String get(String capability) {
        String key = cacheKey(capability);
        CacheEntry entry = cache.get(key);
        if (entry == null) return null;
        if ((System.currentTimeMillis() - entry.atMs()) >= TTL_MS) {
            cache.remove(key);
            return null;
        }
        return entry.resolvedName();
    }

    /**
     * Store a resolved capability result.
     */
    public void put(String capability, String resolvedName) {
        cache.put(cacheKey(capability), new CacheEntry(resolvedName, System.currentTimeMillis()));
    }

    /**
     * Invalidate a specific capability cache entry.
     */
    public void invalidate(String capability) {
        cache.remove(cacheKey(capability));
    }

    /**
     * Invalidate all cached resolutions for the current tenant.
     */
    public void invalidateAll() {
        String prefix = tenantPrefix();
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String cacheKey(String capability) {
        return tenantPrefix() + capability;
    }

    private String tenantPrefix() {
        UUID tenantId = TenantContext.get();
        return (tenantId != null ? tenantId.toString() : NO_TENANT) + ":";
    }
}
