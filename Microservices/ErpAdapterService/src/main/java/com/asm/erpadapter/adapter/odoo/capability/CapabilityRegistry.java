package com.asm.erpadapter.adapter.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Odoo capability registry from {@code odoo-capabilities.json} on the classpath.
 *
 * <p>The JSON maps each {@link CanonicalCapability} name to its metadata:
 * model, type (FIELD/METHOD), ordered candidate names, and optional version bindings.
 *
 * <p>This class is the <b>single source of truth</b> for Odoo version differences.
 * The {@link FieldResolver} and {@link MethodResolver} read from here.
 *
 * <p><b>Declaring a version-specific name</b> — when an Odoo release renames a field or method,
 * pin it here rather than relying on runtime discovery (which costs an RPC round-trip, can be
 * inconclusive, and fails in the middle of a business operation):
 * <pre>
 * "SET_FULL_QUANTITY": {
 *   "model": "stock.picking", "type": "METHOD",
 *   "bindings": [ { "minVersion": 19, "name": "the_odoo19_name" } ],
 *   "candidates": [ "action_set_quantities_to_reservation" ]
 * }
 * </pre>
 * Bindings matching the tenant's detected major (see {@code OdooVersionResolver}) are tried first;
 * {@code candidates} remain the version-agnostic fallback, so adding a binding is always safe.
 */
@Component
@Slf4j
public class CapabilityRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CapabilityEntry> registry = new HashMap<>();

    /**
     * A version-pinned name for a capability. Lets an operator DECLARE that a given Odoo major uses a
     * specific field/method, instead of leaving the adapter to discover it by trial and error at
     * runtime — the difference between a reviewable config change and a failed delivery sync.
     *
     * <p>{@code minVersion}/{@code maxVersion} are inclusive; either may be null (open-ended).
     */
    public record VersionBinding(Integer minVersion, Integer maxVersion, String name) {
        /** A binding never matches an unknown version (major 0) — we fall back to generic candidates. */
        public boolean matches(int major) {
            if (major <= 0) return false;
            if (minVersion != null && major < minVersion) return false;
            if (maxVersion != null && major > maxVersion) return false;
            return true;
        }
    }

    public record CapabilityEntry(String model, String type, List<String> candidates,
                                  List<VersionBinding> bindings) {
        /** Version-agnostic entry (no bindings) — the common case. */
        public CapabilityEntry(String model, String type, List<String> candidates) {
            this(model, type, candidates, List.of());
        }

        public boolean isField() { return "FIELD".equalsIgnoreCase(type); }
        public boolean isMethod() { return "METHOD".equalsIgnoreCase(type); }

        /**
         * Names to try, most-specific first: bindings declared for this Odoo major, then the
         * version-agnostic candidates as a safety net. Duplicates removed, order preserved.
         */
        public List<String> candidatesFor(int major) {
            if (bindings == null || bindings.isEmpty()) return candidates;
            List<String> ordered = new ArrayList<>();
            for (VersionBinding b : bindings) {
                if (b.matches(major) && !ordered.contains(b.name())) ordered.add(b.name());
            }
            for (String c : candidates) {
                if (!ordered.contains(c)) ordered.add(c);
            }
            return ordered;
        }
    }

    @PostConstruct
    void load() {
        try {
            ClassPathResource resource = new ClassPathResource("odoo-capabilities.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                root.fields().forEachRemaining(entry -> {
                    String capability = entry.getKey();
                    JsonNode node = entry.getValue();
                    String model = node.get("model").asText();
                    String type = node.get("type").asText();
                    List<String> candidates = new ArrayList<>();
                    if (node.has("candidates")) node.get("candidates").forEach(c -> candidates.add(c.asText()));
                    List<VersionBinding> bindings = new ArrayList<>();
                    if (node.has("bindings")) {
                        node.get("bindings").forEach(b -> bindings.add(new VersionBinding(
                                b.has("minVersion") ? b.get("minVersion").asInt() : null,
                                b.has("maxVersion") ? b.get("maxVersion").asInt() : null,
                                b.get("name").asText())));
                    }
                    registry.put(capability, new CapabilityEntry(model, type, candidates, bindings));
                });
                log.info("Odoo capability registry loaded — {} capabilities", registry.size());
            }
        } catch (Exception e) {
            log.error("Failed to load odoo-capabilities.json: {}", e.getMessage(), e);
        }
    }

    /** Version-aware candidate list for a capability (see {@link CapabilityEntry#candidatesFor}). */
    public List<String> getCandidates(String capability, int major) {
        return getRequired(capability).candidatesFor(major);
    }

    /**
     * Get the registry entry for a capability.
     * @return the entry, or null if the capability is unknown.
     */
    public CapabilityEntry get(String capability) {
        return registry.get(capability);
    }

    /**
     * Get the registry entry for a capability, throwing if unknown.
     */
    public CapabilityEntry getRequired(String capability) {
        CapabilityEntry entry = registry.get(capability);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown capability: " + capability
                    + ". Registered: " + registry.keySet());
        }
        return entry;
    }

    /**
     * Get the model name for a capability.
     */
    public String getModel(String capability) {
        return getRequired(capability).model();
    }

    /**
     * Get the candidate names for a capability.
     */
    public List<String> getCandidates(String capability) {
        return getRequired(capability).candidates();
    }

    /**
     * Check if a capability exists in the registry.
     */
    public boolean contains(String capability) {
        return registry.containsKey(capability);
    }

    /**
     * Return all registered capability names.
     */
    public java.util.Set<String> capabilityNames() {
        return registry.keySet();
    }
}
