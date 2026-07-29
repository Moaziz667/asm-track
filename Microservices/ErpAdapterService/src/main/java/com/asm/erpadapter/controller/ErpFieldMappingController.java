package com.asm.erpadapter.controller;

import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.ErpFieldMappingService;
import com.asm.erpadapter.mapping.ErpFieldCatalog;
import com.asm.erpadapter.mapping.FieldMappingResolver;
import com.asm.erpadapter.routing.ErpProviderRouter;
import com.asm.erpadapter.security.TenantContext;
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
@RequestMapping("/api/erp/field-mappings")
@Tag(name = "ERP Field Mappings", description = "Map ASM business fields onto a customer's ERP fields")
@RequiredArgsConstructor
@Slf4j
public class ErpFieldMappingController {

    private final ErpFieldMappingService service;
    private final ErpProviderRouter router;
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

    /** The ASM vocabulary, so the screen can render one row per field without hardcoding the list. */
    @GetMapping("/canonical-fields")
    @Operation(summary = "The ASM business fields that can be mapped")
    public ResponseEntity<List<Map<String, Object>>> canonicalFields() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CanonicalField f : CanonicalField.values()) {
            out.add(Map.of("field", f.name(), "scope", f.scope().name()));
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
            out.put(m, fieldCatalog.fieldsOf(m));
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
            @RequestParam(defaultValue = "odoo") String provider) {
        return ResponseEntity.ok(service.list(requireTenant(), provider));
    }

    @PostMapping
    @Operation(summary = "Create or replace one field mapping")
    public ResponseEntity<?> upsert(@RequestBody FieldMappingRequest request) {
        UUID tenantId = requireTenant();
        String provider = request.provider() != null && !request.provider().isBlank()
                ? request.provider().trim().toLowerCase() : "odoo";
        try {
            return ResponseEntity.ok(service.upsert(tenantId, provider,
                    request.canonicalField(), request.customKey(),
                    request.sourcePath(), request.readAs(), request.updatedBy()));
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
                                       @RequestParam(defaultValue = "odoo") String provider) {
        service.delete(requireTenant(), provider, canonicalField);
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
            String updatedBy
    ) {}
}
