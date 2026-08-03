package com.asm.erpadapter.mapping;

import com.asm.erpadapter.entity.ErpFieldMapping;
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
