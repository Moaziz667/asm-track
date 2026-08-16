package com.asm.erpadapter.controller;

import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.ErpFieldMappingService;
import com.asm.erpadapter.mapping.ErpFieldCatalog;
import com.asm.erpadapter.mapping.FieldMappingResolver;
import com.asm.erpadapter.routing.ErpProviderRouter;
import com.asm.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the integrator says, for one customer, in which ERP field each piece of business data lives.
 *
 * <p>Sibling of {@link ErpMappingController} and deliberately a separate endpoint: that one overrides
 * <em>capabilities</em> (which method validates a transfer) and a mistake there breaks stock; this one
 * overrides where a <em>value</em> is read from, and a mistake shows a wrong label. Two endpoints so
 * the UI can be two screens, because putting them together would let someone relabelling a customer
 * reference corrupt quantities.
 *
 * <p>Internal, service-to-service: the admin UI reaches these through AppBackend, which owns the
 * caller's permission check. The tenant always comes from {@link TenantContext}, never from the body,
 * so no caller can write another tenant's mapping.
 */
@RestController
@RequestMapping("/api/v1/erp/field-mappings")
@Tag(name = "ERP Field Mappings", description = "Map ASM business fields onto a customer's ERP fields")
@RequiredArgsConstructor
@Slf4j
public class ErpFieldMappingController {

    private final ErpFieldMappingService service;
    private final ErpProviderRouter router;
    private final com.asm.erpadapter.mapping.type.CanonicalConverterRegistry registry;
    private final List<ErpFieldCatalog> catalogs;
    private final List<FieldMappingResolver> resolvers;

    /**
     * The catalogue and resolver for the tenant's own ERP.
     *
     * <p>Both used to be the Odoo implementation, injected by concrete type — which was invisible
     * while Odoo was the only provider with a mapping, and would have quietly served Odoo's models
     * to an ERPNext tenant the moment a second one existed.
     */
    private java.util.Optional<ErpFieldCatalog> catalog() {
        String provider = router.provider();
        return catalogs.stream().filter(c -> c.provider().equalsIgnoreCase(provider)).findFirst();
    }

    private java.util.Optional<FieldMappingResolver> resolver() {
        String provider = router.provider();
        return resolvers.stream().filter(r -> r.provider().equalsIgnoreCase(provider)).findFirst();
    }

    /**
     * The ASM vocabulary, so the screen can render one row per field without hardcoding the list.
     *
     * <p>Each row carries where its value comes from today, so an unmapped field can say
     * "défaut · res.partner.phone" rather than a bare "défaut". Knowing what you are about to
     * override is most of the decision, and it is per-provider: the same field reads from
     * {@code partner_id.phone} on Odoo and {@code Sales Order.contact_mobile} on ERPNext.
     */
    @GetMapping("/canonical-fields")
    @Operation(summary = "The ASM business fields that can be mapped, with their built-in source")
    public ResponseEntity<List<Map<String, Object>>> canonicalFields() {
        FieldMappingResolver r = resolver().orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CanonicalField f : CanonicalField.values()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("field", f.name());
            row.put("scope", f.scope().name());
            // The declared canonical type — part of the contract, identical for every tenant. The
            // picker needs it to say what a field expects before anything is chosen.
            row.put("type", f.type().name());
            // Absent rather than guessed when no provider is configured: an empty hint reads as
            // "not known yet", where a wrong one would send someone to the wrong field.
            if (r != null) row.put("defaultSource", r.defaultSourceFor(f));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * The customer's own ERP fields, for the dropdown — including their {@code x_*} fields, which are
     * precisely the ones no probe could have guessed.
     */
    @GetMapping("/available-fields")
    @Operation(summary = "Fields available on the tenant's ERP, for the mapping dropdown")
    public ResponseEntity<Map<String, Object>> availableFields(@RequestParam(required = false) String model) {
        requireTenant();
        ErpFieldCatalog fieldCatalog = catalog().orElse(null);
        if (fieldCatalog == null) return ResponseEntity.ok(Map.of());

        List<String> models = (model != null && !model.isBlank())
                ? List.of(model.trim())
                : addressableModels();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String m : models) {
            // Each field carries its normalised type alongside the ERP's own name, so the picker can
            // grey out what the save would refuse instead of letting someone find out on submit.
            out.put(m, fieldCatalog.fieldsOf(m).stream().map(f -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", f.name());
                row.put("label", f.label());
                row.put("type", f.type());
                row.put("sourceType", fieldCatalog.normalize(f.type()).name());
                row.put("relation", f.relation());
                row.put("custom", f.custom());
                return row;
            }).toList());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Which documents each scope may read from, and which one a bare path hangs off.
     *
     * <p>Served rather than duplicated in the frontend: the picker has to store the exact path shape
     * the resolver will later parse, and the two disagreeing does not fail — it writes a mapping that
     * resolves against a document with no such field and reads as an empty ERP.
     */
    @GetMapping("/scopes")
    @Operation(summary = "The documents each canonical scope may be mapped from")
    public ResponseEntity<Map<String, Object>> scopes() {
        requireTenant();
        FieldMappingResolver r = resolver().orElse(null);
        if (r == null) return ResponseEntity.ok(Map.of());

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (CanonicalField.Scope scope : CanonicalField.Scope.values()) {
            FieldMappingResolver.MappingScope s = r.scopeFor(scope);
            out.put(scope.name(), Map.of("primary", s.primary(), "addressable", s.addressable()));
        }
        return ResponseEntity.ok(out);
    }

    /** Every document in scope for any field — what the picker asks the catalogue about. */
    private List<String> addressableModels() {
        FieldMappingResolver r = resolver().orElse(null);
        if (r == null) return List.of();
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (CanonicalField.Scope scope : CanonicalField.Scope.values()) {
            all.addAll(r.scopeFor(scope).addressable());
        }
        return List.copyOf(all);
    }

    @GetMapping
    @Operation(summary = "The current tenant's field mappings")
    public ResponseEntity<List<ErpFieldMapping>> list(
            @RequestParam(required = false) String provider) {
        return ResponseEntity.ok(service.list(requireTenant(), providerOrTenants(provider)));
    }

    /**
     * The provider a request is about: what the caller asked for, else the tenant's own.
     *
     * <p>This used to default to the literal {@code "odoo"}, which was invisible while Odoo was the
     * only provider with a mapping and actively wrong afterwards. An ERPNext tenant's screen listed
     * Odoo's mappings — so it looked empty — and a mapping created from it was stored under
     * {@code odoo}, where no ERPNext read would ever look for it. Silently writing to the wrong
     * provider is worse than refusing, so the tenant's configured ERP is the only sane default.
     */
    private String providerOrTenants(String requested) {
        if (requested != null && !requested.isBlank()) return requested.trim().toLowerCase();
        String tenants = router.provider();
        return "none".equals(tenants) ? "odoo" : tenants;
    }

    /**
     * Which source types may fill which canonical type, derived from the registered converters.
     *
     * <p>Served rather than duplicated in the frontend for the same reason as {@code /scopes}: a copy
     * would drift, and the disagreement would be silent — the picker offering a field the save then
     * refuses. The server stays the authority; this only lets the screen show the verdict before the
     * click instead of after it.
     */
    @GetMapping("/type-matrix")
    @Operation(summary = "Compatibility of each ERP source type with each canonical type")
    public ResponseEntity<Map<String, Map<String, String>>> typeMatrix() {
        Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
        registry.matrix().forEach((target, row) -> {
            Map<String, String> bySource = new java.util.LinkedHashMap<>();
            row.forEach((source, compat) -> bySource.put(source.name(), compat.name()));
            out.put(target.name(), bySource);
        });
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @Operation(summary = "Create or replace one field mapping")
    public ResponseEntity<?> upsert(@RequestBody FieldMappingRequest request) {
        UUID tenantId = requireTenant();
        String provider = providerOrTenants(request.provider());
        try {
            return ResponseEntity.ok(service.upsert(tenantId, provider,
                    request.canonicalField(), request.customKey(),
                    request.sourcePath(), request.readAs(), request.updatedBy(),
                    Boolean.TRUE.equals(request.acceptLossy())));
        } catch (ErpFieldMappingService.LossyMappingException e) {
            // Distinct from a refusal: the mapping is allowed, it just costs something. The UI turns
            // this into a confirmation and retries with acceptLossy — a plain 400 would read as a
            // dead end and the integrator would go looking for another field.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(), "errorCode", "LOSSY_FIELD_MAPPING", "status", 400));
        } catch (IllegalArgumentException e) {
            // The message is written for the integrator, so hand it back rather than a generic 400.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(), "errorCode", "INVALID_FIELD_MAPPING", "status", 400));
        }
    }

    /** Removing a mapping restores the shipped default; it does not blank the field. */
    @DeleteMapping("/{canonicalField}")
    @Operation(summary = "Remove a mapping and fall back to the default")
    public ResponseEntity<Void> delete(@PathVariable String canonicalField,
                                       @RequestParam(required = false) String provider) {
        service.delete(requireTenant(), providerOrTenants(provider), canonicalField);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/by-id/{id}")
    @Operation(summary = "Remove a custom (non-canonical) mapping by id")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        service.deleteById(requireTenant(), id);
        return ResponseEntity.noContent().build();
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing or invalid X-Company-Id header");
        }
        return tenantId;
    }

    public record FieldMappingRequest(
            String provider,
            String canonicalField,
            String customKey,
            String sourcePath,
            String readAs,
            String updatedBy,
            /** Set once the integrator has seen what a lossy conversion costs and accepted it. */
            Boolean acceptLossy
    ) {}
}
