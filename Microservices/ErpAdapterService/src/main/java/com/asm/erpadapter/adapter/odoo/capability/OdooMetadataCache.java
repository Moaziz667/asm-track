package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches Odoo model metadata (available fields) per tenant to avoid repeated {@code fields_get()} calls.
 *
 * <p>Each tenant gets its own cache entry keyed by {@code tenantId:model}. TTL is 30 minutes —
 * Odoo instances almost never add/remove fields at runtime, and a field change is picked up
 * after TTL expiry or explicit invalidation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooMetadataCache {

    private final OdooJsonRpcClient rpc;

    private record FieldCache(Set<String> fields, long atMs) {}

    private final ConcurrentHashMap<String, FieldCache> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30 * 60 * 1000L; // 30 min
    private static final String NO_TENANT = "__no_tenant__";

    /**
     * Get the set of available field names for a model on the current tenant's Odoo instance.
     * Uses cache if fresh; otherwise queries Odoo via {@code fields_get()}.
     */
    public Set<String> getAvailableFields(String model) {
        String key = cacheKey(model);
        long now = System.currentTimeMillis();
        FieldCache cached = cache.get(key);
        if (cached != null && (now - cached.atMs()) < TTL_MS) {
            return cached.fields();
        }

        Set<String> fields = fetchFields(model);
        cache.put(key, new FieldCache(fields, now));
        return fields;
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
