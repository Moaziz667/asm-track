package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.entity.ErpMapping;
import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;


/**
 * Orchestrates capability resolution following the enterprise flow:
 *
 * <pre>
 * 1. CapabilityCache lookup (fast path)
 * 2. ErpMapping customer override (DB)
 * 3. CapabilityRegistry defaults (JSON)
 * 4. Odoo discovery (fields_get for fields, try/catch for methods)
 * 5. Cache result
 * </pre>
 *
 * <p>Workflow code uses this class — it never talks to Odoo directly.
 * It delegates to {@link FieldResolver} for fields and {@link MethodResolver} for methods.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CapabilityResolver {

    private final CapabilityCache cache;
    private final CapabilityRegistry registry;
    private final FieldResolver fieldResolver;
    private final MethodResolver methodResolver;
    private final ErpMappingService mappingService;

    /**
     * Resolve a capability to its Odoo field or method name.
     *
     * @param capability the canonical capability name
     * @return the resolved Odoo name (field or method)
     */
    public String resolve(CanonicalCapability capability) {
        return resolve(capability.name());
    }

    /**
     * Resolve a capability by string name.
     *
     * @param capability the capability name (e.g. "DONE_QUANTITY")
     * @return the resolved Odoo name (field or method)
     */
    public String resolve(String capability) {
        // 1. Cache lookup
        String cached = cache.get(capability);
        if (cached != null) {
            log.debug("CapabilityResolver: cache HIT capability={} resolved={}", capability, cached);
            return cached;
        }

        // 2. Customer override (DB)
        String resolved = resolveFromMapping(capability);
        if (resolved != null) {
            cache.put(capability, resolved);
            log.debug("CapabilityResolver: customer override capability={} resolved={}", capability, resolved);
            return resolved;
        }

        // 3-4. Default resolution via registry + Odoo discovery
        resolved = resolveDefault(capability);

        // 5. Cache result
        cache.put(capability, resolved);
        log.debug("CapabilityResolver: default resolution capability={} resolved={}", capability, resolved);
        return resolved;
    }

    /**
     * Resolve a capability with a specific customer override.
     */
    public String resolveWithOverride(CanonicalCapability capability, String customerOverride) {
        return resolveWithOverride(capability.name(), customerOverride);
    }

    /**
     * Resolve with a specific customer override.
     */
    public String resolveWithOverride(String capability, String customerOverride) {
        if (customerOverride == null || customerOverride.isBlank()) {
            return resolve(capability);
        }

        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);
        String resolved;

        if (entry.isField()) {
            resolved = fieldResolver.resolveWithOverride(capability, customerOverride);
        } else {
            resolved = methodResolver.resolveWithOverride(capability, customerOverride);
        }

        cache.put(capability, resolved);
        return resolved;
    }

    /**
     * Check if a capability exists in the registry.
     */
    public boolean supports(CanonicalCapability capability) {
        return registry.contains(capability.name());
    }

    /**
     * Get the model name for a capability.
     */
    public String getModel(CanonicalCapability capability) {
        return registry.getModel(capability.name());
    }

    /**
     * Get all candidate names for a method capability (for fallback execution).
     */
    public java.util.List<String> getCandidates(CanonicalCapability capability) {
        return registry.getCandidates(capability.name());
    }

    private String resolveFromMapping(String capability) {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return null;

        Optional<ErpMapping> mapping = mappingService.findByTenantAndCapability(tenantId, capability);
        if (mapping.isEmpty()) return null;

        ErpMapping m = mapping.get();
        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);

        if (entry.isField()) {
            return fieldResolver.resolveWithOverride(capability, m.getOdooName());
        } else {
            return methodResolver.resolveWithOverride(capability, m.getOdooName());
        }
    }

    private String resolveDefault(String capability) {
        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);

        if (entry.isField()) {
            return fieldResolver.resolve(capability);
        } else {
            return methodResolver.resolve(capability);
        }
    }
}
