package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Caches Odoo model metadata (available fields) per tenant to avoid repeated {@code fields_get()} calls.
 *
 * <p>Each tenant gets its own cache entry keyed by {@code tenantId:model}. TTL is 30 minutes —
 * Odoo instances almost never add/remove fields at runtime, and a field change is picked up
 * after TTL expiry or explicit invalidation.
 *
 * <p>Uses single-flight pattern via {@link CompletableFuture} to prevent cache stampede
 * (multiple threads performing redundant Odoo RPC calls for the same model).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooMetadataCache {

    private final OdooJsonRpcClient rpc;

    private record CacheEntry(Set<String> fields, long atMs) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Set<String>>> inFlight = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30 * 60 * 1000L; // 30 min
    private static final String NO_TENANT = "__no_tenant__";

    /**
     * Get the set of available field names for a model on the current tenant's Odoo instance.
     * Uses cache if fresh; otherwise queries Odoo via {@code fields_get()}.
     * Single-flight: concurrent requests for the same model share one Odoo RPC call.
     */
    public Set<String> getAvailableFields(String model) {
        String key = cacheKey(model);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(key);
        if (cached != null && (now - cached.atMs()) < TTL_MS) {
            return Collections.unmodifiableSet(cached.fields());
        }

        CompletableFuture<Set<String>> future = inFlight.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Set<String> fields = fetchFields(model);
                        cache.put(key, new CacheEntry(fields, System.currentTimeMillis()));
                        return fields;
                    } finally {
                        inFlight.remove(k);
                    }
                }));

        try {
            return future.get();
        } catch (Exception e) {
            log.warn("OdooMetadataCache: single-flight failed for model={} reason={}", model, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Check if a specific field exists on a model.
     */
    public boolean hasField(String model, String fieldName) {
        return getAvailableFields(model).contains(fieldName);
    }

    /**
     * Invalidate the cache for a specific model on the current tenant.
     */
    public void invalidate(String model) {
        cache.remove(cacheKey(model));
    }

    /**
     * Invalidate all cached metadata for the current tenant.
     */
    public void invalidateAll() {
        String prefix = tenantPrefix();
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @SuppressWarnings("unchecked")
    private Set<String> fetchFields(String model) {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, "fields_get",
                    List.of(), Map.of("attributes", List.of("type"))));
            if (resp == null || resp.containsKey("error")) {
                log.warn("OdooMetadataCache: fields_get failed for model={}", model);
                return Set.of();
            }
            Object result = resp.get("result");
            if (result instanceof Map<?, ?> fieldsMap) {
                Set<String> fields = new HashSet<>();
                fieldsMap.keySet().forEach(k -> fields.add(String.valueOf(k)));
                log.debug("OdooMetadataCache: model={} fields={}", model, fields.size());
                return fields;
            }
            return Set.of();
        } catch (Exception e) {
            log.warn("OdooMetadataCache: fields_get exception for model={} reason={}", model, e.getMessage());
            return Set.of();
        }
    }

    private String cacheKey(String model) {
        return tenantPrefix() + model;
    }

    private String tenantPrefix() {
        UUID tenantId = TenantContext.get();
        return (tenantId != null ? tenantId.toString() : NO_TENANT) + ":";
    }
}
