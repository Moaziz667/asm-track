package com.asm.erpadapter.controller;

import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.ErpFieldMappingService;
import com.asm.erpadapter.mapping.OdooFieldCatalog;
import com.asm.erpadapter.mapping.OdooFieldMappingResolver;
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
    private final OdooFieldCatalog catalog;

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
        List<String> models = (model != null && !model.isBlank())
                ? List.of(model.trim())
                : OdooFieldMappingResolver.addressableModels();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String m : models) {
            out.put(m, catalog.fieldsOf(m));
        }
        return ResponseEntity.ok(out);
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
