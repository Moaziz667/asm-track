package com.asm.erpadapter.mapping;

import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.MappingTypeChecker;
import com.asm.erpadapter.repository.ErpFieldMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes a tenant's business-field mappings.
 *
 * <p>Validation lives here rather than in the controller because a bad mapping is not merely a bad
 * request: it silently changes what every future import contains. The checks below are the ones that
 * distinguish "this will not work" from "this will work but wrongly" — the second being the dangerous
 * kind, since nobody looks at a field that quietly stopped being filled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpFieldMappingService {

    /** Mirrors {@code OdooFieldMappingResolver}: a path deeper than this is refused, not truncated. */
    private static final int MAX_PATH_DEPTH = 4;
    private static final int MAX_CUSTOM_KEYS = 20;

    private final ErpFieldMappingRepository repository;
    private final MappingTypeChecker typeChecker;
    private final List<FieldMappingResolver> resolvers;

    public List<ErpFieldMapping> list(UUID tenantId, String provider) {
        return repository.findByTenantIdAndProvider(tenantId, provider);
    }

    public Optional<ErpFieldMapping> find(UUID tenantId, String provider, String canonicalField) {
        return repository.findByTenantIdAndProviderAndCanonicalField(tenantId, provider, canonicalField);
    }

    /**
     * Create or replace one mapping.
     *
     * @throws IllegalArgumentException with a message meant to be shown to the integrator
     */
    @Transactional
    public ErpFieldMapping upsert(UUID tenantId, String provider, String canonicalField,
                                  String customKey, String sourcePath, String readAs, String actor) {
        return upsert(tenantId, provider, canonicalField, customKey, sourcePath, readAs, actor, false);
    }

    /**
     * @param acceptLossy the integrator has been shown what a lossy conversion costs and accepted it.
     *                    Required rather than assumed: a quantity losing its decimals is a decision
     *                    with a customer on the other end, and defaulting to yes would make the
     *                    warning decorative.
     */
    @Transactional
    public ErpFieldMapping upsert(UUID tenantId, String provider, String canonicalField,
                                  String customKey, String sourcePath, String readAs, String actor,
                                  boolean acceptLossy) {
        String path = requirePath(sourcePath);
        String kind = SourceKind.from(readAs).name();

        boolean isCanonical = canonicalField != null && !canonicalField.isBlank();
        boolean isCustom = customKey != null && !customKey.isBlank();
        if (isCanonical == isCustom) {
            // Both would make the row ambiguous; neither leaves the value with nowhere to land.
            throw new IllegalArgumentException(
                    "Indiquez soit un champ ASM (canonicalField), soit un nom personnalisé (customKey) — pas les deux.");
        }

        String canonical = null;
        if (isCanonical) {
            canonical = canonicalField.trim().toUpperCase();
            try {
                CanonicalField.valueOf(canonical);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Champ ASM inconnu : " + canonicalField);
            }
        } else {
            enforceCustomKeyBudget(tenantId, provider, customKey.trim());
        }

        ErpFieldMapping existing = isCanonical
                ? repository.findByTenantIdAndProviderAndCanonicalField(tenantId, provider, canonical).orElse(null)
                : repository.findByTenantIdAndProvider(tenantId, provider).stream()
                        .filter(m -> customKey.trim().equals(m.getCustomKey()))
                        .findFirst().orElse(null);

        ErpFieldMapping row = existing != null ? existing : ErpFieldMapping.builder()
                .tenantId(tenantId)
                .provider(provider)
                .canonicalField(canonical)
                .customKey(isCustom ? customKey.trim() : null)
                .build();

        row.setSourcePath(path);
        row.setReadAs(kind);
        row.setUpdatedBy(actor);
        row.setSourceType(checkType(provider, canonical, path, acceptLossy));

        ErpFieldMapping saved = repository.save(row);
        log.info("Field mapping saved — tenant={} provider={} target={} path={} readAs={} by={}",
                tenantId, provider, isCanonical ? canonical : customKey, path, kind, actor);
        return saved;
    }

    /** Removing a mapping restores the shipped default — it does not blank the field. */
    @Transactional
    public void delete(UUID tenantId, String provider, String canonicalField) {
        repository.deleteByTenantIdAndProviderAndCanonicalField(
                tenantId, provider, canonicalField.trim().toUpperCase());
        log.info("Field mapping removed — tenant={} provider={} field={} (default restored)",
                tenantId, provider, canonicalField);
    }

    @Transactional
    public void deleteById(UUID tenantId, Long id) {
        repository.findById(id)
                .filter(m -> m.getTenantId().equals(tenantId))   // never let a tenant delete another's row
                .ifPresent(repository::delete);
    }

    // ── Validation ────────────────────────────────────────────────────────────────────────────────

    /**
     * Refuse a mapping the converters cannot honour, and record the type it was built against.
     *
     * <p>This is the check that stops a phone number reaching a quantity. It runs here rather than in
     * the picker alone because the picker is advisory — an API client, a replayed request or a stale
     * browser tab all reach this method, and a rule enforced only in the UI is not a rule.
     *
     * @return the normalised source type to store as the drift baseline, or null when the ERP could
     *         not be asked — absence must not later read as "unchanged"
     */
    private String checkType(String provider, String canonicalField, String path, boolean acceptLossy) {
        if (canonicalField == null) return null;   // an extra has no canonical type to satisfy

        CanonicalField field = CanonicalField.valueOf(canonicalField);
        MappingTypeChecker.Verdict verdict =
                typeChecker.check(provider, field, modelOf(path, provider, field), lastSegment(path));

        if (verdict.isRefused()) {
            throw new IllegalArgumentException(verdict.message());
        }
        if (verdict.compatibility() == Compatibility.LOSSY && !acceptLossy && verdict.message() != null) {
            // Surfaced as a refusal the caller can retry with acceptLossy=true — the UI turns this
            // into a confirmation rather than a dead end.
            throw new LossyMappingException(verdict.message());
        }
        return verdict.sourceType() != null ? verdict.sourceType().name() : null;
    }

    /** Raised when a mapping converts but loses something, and nobody has said that is acceptable. */
    public static class LossyMappingException extends RuntimeException {
        public LossyMappingException(String message) { super(message); }
    }

    /** The document the final segment of {@code path} belongs to. */
    private String modelOf(String path, String provider, CanonicalField field) {
        String expr = path;
        String model = null;
        int colon = path.indexOf(':');
        if (colon > 0) {
            model = path.substring(0, colon).trim();
            expr = path.substring(colon + 1).trim();
        }
        // A path that walks a relation ends on a model we cannot name without following it, and
        // following it needs live records. Leave it unverified rather than judged against the wrong
        // document — the preview will still run the real conversion.
        if (expr.contains(".")) return null;
        if (model != null) return model;

        return resolvers.stream()
                .filter(r -> r.provider().equalsIgnoreCase(provider))
                .findFirst()
                .map(r -> r.scopeFor(field.scope()).primary())
                .orElse(null);
    }

    private static String lastSegment(String path) {
        String expr = path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
        String[] parts = expr.split("\\.");
        return parts[parts.length - 1].trim();
    }

    private String requirePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("Le chemin source est obligatoire.");
        }
        String path = sourcePath.trim();

        String expr = path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
        if (expr.isBlank()) {
            throw new IllegalArgumentException("Le chemin source est incomplet : " + path);
        }
        String[] segments = expr.split("\\.");
        if (segments.length > MAX_PATH_DEPTH) {
            throw new IllegalArgumentException(
                    "Chemin trop profond (max " + MAX_PATH_DEPTH + " niveaux) : " + path);
        }
        for (String s : segments) {
            if (s.isBlank()) {
                throw new IllegalArgumentException("Chemin mal formé : " + path);
            }
        }
        return path;
    }

    /**
     * Extras are display-only and live in a JSON bag on every order row, so an unbounded number of
     * them bloats each record for no benefit. The cap is a nudge: past twenty, the field probably
     * deserves to become a real ASM field rather than another line in the bag.
     */
    private void enforceCustomKeyBudget(UUID tenantId, String provider, String key) {
        List<ErpFieldMapping> extras =
                repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(tenantId, provider);
        boolean alreadyExists = extras.stream().anyMatch(m -> key.equals(m.getCustomKey()));
        if (!alreadyExists && extras.size() >= MAX_CUSTOM_KEYS) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_CUSTOM_KEYS + " champs personnalisés atteinte.");
        }
    }
}
