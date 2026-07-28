package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the correct Odoo method name for a capability.
 *
 * <p>Resolution order: names declared for the tenant's Odoo major version
 * ({@link CapabilityRegistry.VersionBinding}) first, then the version-agnostic candidates. Because
 * Odoo exposes no "list methods" RPC (unlike fields via {@code fields_get()}), existence is probed —
 * but the probe is designed to be <b>side-effect free and unambiguous</b>:
 *
 * <ul>
 *   <li><b>Empty recordset.</b> The probe calls {@code method([])}, never {@code method([0])}. On a
 *       missing id Odoo raises {@code MissingError} whose message is "Record <b>does not exist</b> or
 *       has been deleted" — which the old substring test read as "method not found", declaring
 *       perfectly good methods missing. Whether that happened depended on whether the method
 *       dereferenced a field before returning, which is why only some capabilities broke and it
 *       looked version-specific. With an empty recordset there is no record to be missing, and no
 *       business logic runs on any real row.</li>
 *   <li><b>Structured discrimination.</b> The verdict is taken from the JSON-RPC error's
 *       {@code data.name} (the Python exception class) — {@code AttributeError} means the method
 *       does not exist; {@code MissingError}, {@code UserError}, {@code ValidationError},
 *       {@code AccessError}, {@code ValueError} all mean it <em>does</em> (it ran and objected).
 *       Message text is only a last-resort fallback: it is localized and rewritten between releases,
 *       so matching on it breaks on a French Odoo or a minor upgrade.</li>
 *   <li><b>Fail closed on doubt.</b> A transport failure is INCONCLUSIVE, never "missing" — the old
 *       code returned false on a null response, so one network blip could cache a bogus negative for
 *       the whole TTL and disable a working capability.</li>
 * </ul>
 *
 * <p>Results are cached per tenant+capability by {@link CapabilityResolver}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MethodResolver {

    private final OdooJsonRpcClient rpc;
    private final CapabilityRegistry registry;
    private final OdooVersionResolver versionResolver;

    /** Outcome of probing one candidate method. */
    public enum Probe {
        /** The method exists (it ran, or raised a business/permission error). */
        EXISTS,
        /** Proven absent — Odoo raised AttributeError. */
        MISSING,
        /** Could not tell (transport error). Must not be cached as a verdict. */
        INCONCLUSIVE
    }

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

        String model = entry.model();
        int major = versionResolver.major();
        List<String> candidates = entry.candidatesFor(major);

        for (String candidate : candidates) {
            Probe probe = probe(model, candidate);
            if (probe == Probe.EXISTS) {
                log.debug("MethodResolver: capability={} model={} odooMajor={} resolved={}",
                        capability, model, major, candidate);
                return candidate;
            }
            if (probe == Probe.INCONCLUSIVE) {
                // Never let a transport hiccup masquerade as "capability unavailable": that verdict
                // would be cached and would disable a working integration until the TTL expires.
                throw new CapabilityProbeException(capability, model, candidate);
            }
        }

        throw new MethodResolutionException(capability, model, candidates, major);
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

        if (probe(model, customerOverride) == Probe.EXISTS) {
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
     * Probe whether {@code method} exists on {@code model}, without touching any real record.
     *
     * <p>Called on an EMPTY recordset: an existing method iterates nothing and returns (or raises a
     * business error such as "Expected singleton"); a non-existent one makes Odoo's RPC dispatcher
     * raise {@code AttributeError}. No row is read, no row is written — the probe cannot have side
     * effects even if the method is destructive.
     */
    public Probe probe(String model, String method) {
        Map<String, Object> resp;
        try {
            resp = rpc.callRpc(rpc.buildArgs(model, method, List.of(List.of())));
        } catch (Exception e) {
            log.warn("MethodResolver: probe transport failure model={} method={} reason={}",
                    model, method, e.getMessage());
            return Probe.INCONCLUSIVE;
        }
        // callRpc swallows transport errors and returns null — that is "we don't know", not "absent".
        if (resp == null) {
            log.warn("MethodResolver: probe inconclusive (no response) model={} method={}", model, method);
            return Probe.INCONCLUSIVE;
        }
        if (!resp.containsKey("error")) return Probe.EXISTS;

        Object error = resp.get("error");
        String exceptionClass = extractExceptionClass(error);
        if (exceptionClass != null) {
            // Structured, locale-independent verdict. Only AttributeError proves absence; every other
            // exception means the method resolved and then objected (missing record, permission,
            // validation, wrong arity...) — i.e. it exists.
            boolean missing = exceptionClass.contains("AttributeError");
            log.debug("MethodResolver: probe model={} method={} exception={} verdict={}",
                    model, method, exceptionClass, missing ? "MISSING" : "EXISTS");
            return missing ? Probe.MISSING : Probe.EXISTS;
        }

        // No exception class in the payload (non-standard error shape): fall back to the ONE message
        // signature that unambiguously means "no such attribute". Deliberately NOT matching
        // "does not exist" — that is MissingError's wording for a missing RECORD and misclassifying
        // it is exactly the bug this method replaces.
        String msg = extractErrorMessage(error);
        if (msg != null && (msg.contains("has no attribute") || msg.contains("non-existent method"))) {
            return Probe.MISSING;
        }
        log.debug("MethodResolver: probe model={} method={} unrecognised error shape, assuming EXISTS: {}",
                model, method, msg);
        return Probe.EXISTS;
    }

    /** The Python exception class from an Odoo JSON-RPC fault, e.g. {@code builtins.AttributeError}. */
    private String extractExceptionClass(Object error) {
        if (error instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?> dm) {
            Object name = dm.get("name");
            if (name != null && !String.valueOf(name).isBlank()) return String.valueOf(name);
        }
        return null;
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
     * No candidate method exists on this Odoo instance — a structural incompatibility between the
     * adapter and the tenant's Odoo version. PERMANENT: retrying cannot help, so callers must fail
     * the operation immediately with an actionable message instead of burning the retry budget.
     * The remedy is a version binding in {@code odoo-capabilities.json} or a tenant ErpMapping
     * override — both configuration, not code.
     */
    public static class MethodResolutionException extends RuntimeException {
        private final String capability;
        private final String model;
        private final List<String> candidates;
        private final int odooMajor;

        public MethodResolutionException(String capability, String model, List<String> candidates, int odooMajor) {
            super("Odoo " + (odooMajor > 0 ? odooMajor : "?") + " does not provide capability '" + capability
                    + "' on model '" + model + "'. Tried: " + candidates
                    + ". Declare the correct name for this version in odoo-capabilities.json"
                    + " (bindings) or as a tenant ErpMapping override.");
            this.capability = capability;
            this.model = model;
            this.candidates = candidates;
            this.odooMajor = odooMajor;
        }

        public String getCapability() { return capability; }
        public String getModel() { return model; }
        public List<String> getCandidates() { return candidates; }
        public int getOdooMajor() { return odooMajor; }
    }

    /**
     * The instance could not be probed (transport failure). TRANSIENT — unlike
     * {@link MethodResolutionException} this one SHOULD be retried, and nothing is cached, so a
     * network blip can never leave a capability permanently marked unavailable.
     */
    public static class CapabilityProbeException extends RuntimeException {
        public CapabilityProbeException(String capability, String model, String method) {
            super("Could not determine availability of '" + method + "' on '" + model
                    + "' for capability '" + capability + "' — Odoo unreachable. Retryable.");
        }
    }
}
