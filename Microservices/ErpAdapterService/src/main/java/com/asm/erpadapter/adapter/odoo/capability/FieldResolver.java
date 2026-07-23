package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the correct Odoo field name for a capability by querying {@code fields_get()}.
 *
 * <p>Uses {@link OdooMetadataCache} to avoid repeated {@code fields_get()} calls.
 * Falls back to the first available candidate from the {@link CapabilityRegistry}.
 *
 * <p>Thread-safe — uses ConcurrentHashMap for the per-tenant metadata cache.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FieldResolver {

    private final OdooJsonRpcClient rpc;
    private final CapabilityRegistry registry;
    private final OdooMetadataCache metadataCache;

    /**
     * Resolve the correct field name for a capability.
     *
     * @param capability the capability name (e.g. "DONE_QUANTITY")
     * @return the first available field name from the candidates
     * @throws IllegalArgumentException if capability is unknown
     * @throws FieldResolutionException if no candidate field exists on the Odoo instance
     */
    public String resolve(String capability) {
        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);
        if (!entry.isField()) {
            throw new IllegalArgumentException("Capability " + capability + " is a METHOD, not a FIELD. Use MethodResolver.");
        }

        String model = entry.model();
        List<String> candidates = entry.candidates();

        // Get available fields for this model (cached)
        Set<String> availableFields = metadataCache.getAvailableFields(model);

        // Return the first candidate that exists
        for (String candidate : candidates) {
            if (availableFields.contains(candidate)) {
                log.debug("FieldResolver: capability={} model={} resolved={}", capability, model, candidate);
                return candidate;
            }
        }

        throw new FieldResolutionException(capability, model, candidates, availableFields);
    }

    /**
     * Resolve field with a customer override mapping.
     *
     * @param capability the capability name
     * @param customerOverride the customer's custom field name (e.g. "x_delivery_zone")
     * @return the customer override if it exists on the model, otherwise the default resolution
     */
    public String resolveWithOverride(String capability, String customerOverride) {
        if (customerOverride == null || customerOverride.isBlank()) {
            return resolve(capability);
        }

        CapabilityRegistry.CapabilityEntry entry = registry.getRequired(capability);
        String model = entry.model();
        Set<String> availableFields = metadataCache.getAvailableFields(model);

        if (availableFields.contains(customerOverride)) {
            log.debug("FieldResolver: capability={} model={} using customer override={}", capability, model, customerOverride);
            return customerOverride;
        }

        log.warn("FieldResolver: customer override '{}' not found on model '{}', falling back to default", customerOverride, model);
        return resolve(capability);
    }

    /**
     * Check if a field exists on a model.
     */
    public boolean fieldExists(String model, String fieldName) {
        return metadataCache.getAvailableFields(model).contains(fieldName);
    }

    /**
     * Exception thrown when no candidate field is found on the Odoo instance.
     */
    public static class FieldResolutionException extends RuntimeException {
        private final String capability;
        private final String model;
        private final List<String> candidates;
        private final Set<String> available;

        public FieldResolutionException(String capability, String model, List<String> candidates, Set<String> available) {
            super("No field found for capability '" + capability + "' on model '" + model
                    + "'. Candidates: " + candidates + ". Available fields: " + available);
            this.capability = capability;
            this.model = model;
            this.candidates = candidates;
            this.available = available;
        }

        public String getCapability() { return capability; }
        public String getModel() { return model; }
        public List<String> getCandidates() { return candidates; }
        public Set<String> getAvailable() { return available; }
    }
}
