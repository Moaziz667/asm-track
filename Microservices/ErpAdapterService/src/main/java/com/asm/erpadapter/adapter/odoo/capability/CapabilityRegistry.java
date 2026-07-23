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
 * model, type (FIELD/METHOD), and ordered candidate names.
 *
 * <p>This class is the <b>single source of truth</b> for Odoo version differences.
 * The {@link FieldResolver} and {@link MethodResolver} read from here.
 */
@Component
@Slf4j
public class CapabilityRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CapabilityEntry> registry = new HashMap<>();

    public record CapabilityEntry(String model, String type, List<String> candidates) {
        public boolean isField() { return "FIELD".equalsIgnoreCase(type); }
        public boolean isMethod() { return "METHOD".equalsIgnoreCase(type); }
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
                    node.get("candidates").forEach(c -> candidates.add(c.asText()));
                    registry.put(capability, new CapabilityEntry(model, type, candidates));
                });
                log.info("Odoo capability registry loaded — {} capabilities", registry.size());
            }
        } catch (Exception e) {
            log.error("Failed to load odoo-capabilities.json: {}", e.getMessage(), e);
        }
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
