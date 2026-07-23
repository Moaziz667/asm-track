package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the correct Odoo method name for a capability by trying each candidate in order.
 *
 * <p>Unlike {@link FieldResolver} (which uses {@code fields_get()}), the MethodResolver
 * uses <b>try/catch</b>: it calls the first candidate method, and if Odoo returns a
 * "method not found" error, it tries the next candidate.
 *
 * <p>This is necessary because Odoo does not expose available methods via introspection —
 * only fields are available via {@code fields_get()}.
 *
 * <p>Results are cached per tenant+capability to avoid repeated try/catch attempts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MethodResolver {

    private final OdooJsonRpcClient rpc;
    private final CapabilityRegistry registry;

    private record MethodCache(String resolvedMethod, long atMs) {}

    private final ConcurrentHashMap<String, MethodCache> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30 * 60 * 1000L; // 30 min
    private static final String NO_TENANT = "__no_tenant__";

    /**
     * Resolve the correct method name for a capability by trying each candidate.
     *
     * @param capability the capability name (e.g. "CREATE_RETURN")
     * @return the first available method name
     * @throws IllegalArgumentException if capability is unknown or is a FIELD
     * @throws MethodResolutionException if no candidate method is callable
     */
    public String resolve(String capability) {
        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);
        if (!entry.isMethod()) {
            throw new IllegalArgumentException("Capability " + capability + " is a FIELD, not a METHOD. Use FieldResolver.");
        }

        String key = cacheKey(capability);
        long now = System.currentTimeMillis();
        MethodCache cached = cache.get(key);
        if (cached != null && (now - cached.atMs()) < TTL_MS) {
            return cached.resolvedMethod();
        }

        String model = entry.model();
        List<String> candidates = entry.candidates();

        for (String candidate : candidates) {
            if (isMethodCallable(model, candidate)) {
                cache.put(key, new MethodCache(candidate, now));
                log.debug("MethodResolver: capability={} model={} resolved={}", capability, model, candidate);
                return candidate;
            }
        }

        throw new MethodResolutionException(capability, model, candidates);
    }

    /**
     * Resolve with a customer override mapping.
     *
     * @param capability the capability name
     * @param customerOverride the customer's custom method name
     * @return the customer override if callable, otherwise the default resolution
     */
    public String resolveWithOverride(String capability, String customerOverride) {
        if (customerOverride == null || customerOverride.isBlank()) {
            return resolve(capability);
        }

        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);
        String model = entry.model();

        if (isMethodCallable(model, customerOverride)) {
            log.debug("MethodResolver: capability={} model={} using customer override={}", capability, model, customerOverride);
            return customerOverride;
        }

        log.warn("MethodResolver: customer override '{}' not callable on model '{}', falling back to default", customerOverride, model);
        return resolve(capability);
    }

    /**
     * Get all candidate method names for a capability (for fallback execution).
     */
    public List<String> getCandidates(String capability) {
        return registry.getCandidates(capability);
    }

    /**
     * Try to call a method on a test record to check if it exists.
     * Uses a dummy call that will fail gracefully (not modify data).
     */
    @SuppressWarnings("unchecked")
    private boolean isMethodCallable(String model, String method) {
        try {
            // Try calling the method on an empty list — Odoo returns "no record" (not "method not found")
            // if the method exists. A "method not found" error means the method doesn't exist.
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, method, List.of(List.of(0))));
            if (resp == null) return false;

            if (resp.containsKey("error")) {
                Object error = resp.get("error");
                String errorMsg = extractErrorMessage(error);

                // "Method does not exist" or "object has no attribute" → method not available
                if (errorMsg != null && (
                        errorMsg.contains("does not exist") ||
                        errorMsg.contains("has no attribute") ||
                        errorMsg.contains("Missing operator") ||
                        errorMsg.contains("non-existent method"))) {
                    return false;
                }

                // Any other error (access denied, validation error, etc.) → method exists but failed
                return true;
            }

            // No error → method exists and was callable
            return true;
        } catch (Exception e) {
            log.debug("MethodResolver: method check failed model={} method={} reason={}", model, method, e.getMessage());
            return false;
        }
    }

    private String extractErrorMessage(Object error) {
        if (error instanceof Map<?, ?> m) {
            Object data = m.get("data");
            if (data instanceof Map<?, ?> dm) {
                Object msg = dm.get("message");
                if (msg != null) return String.valueOf(msg);
                Object name = dm.get("name");
                if (name != null) return String.valueOf(name);
            }
            Object message = m.get("message");
            if (message != null) return String.valueOf(message);
        }
        return error != null ? String.valueOf(error) : null;
    }

    /**
     * Invalidate a cached resolution for a specific capability (current tenant).
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
        UUID tenantId = com.asm.erpadapter.security.TenantContext.get();
        return (tenantId != null ? tenantId.toString() : NO_TENANT) + ":";
    }

    /**
     * Exception thrown when no candidate method is callable on the Odoo instance.
     */
    public static class MethodResolutionException extends RuntimeException {
        private final String capability;
        private final String model;
        private final List<String> candidates;

        public MethodResolutionException(String capability, String model, List<String> candidates) {
            super("No method found for capability '" + capability + "' on model '" + model
                    + "'. Candidates: " + candidates + ". None were callable.");
            this.capability = capability;
            this.model = model;
            this.candidates = candidates;
        }

        public String getCapability() { return capability; }
        public String getModel() { return model; }
        public List<String> getCandidates() { return candidates; }
    }
}
