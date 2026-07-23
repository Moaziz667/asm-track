package com.asm.erpadapter.controller;

import com.asm.erpadapter.adapter.odoo.ErpMappingService;
import com.asm.erpadapter.entity.ErpMapping;
import com.asm.erpadapter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD endpoints for customer-specific ERP capability mappings.
 *
 * <p>Allows admins to override the default Odoo field/method resolution
 * for a specific tenant. These overrides are checked by {@link com.asm.erpadapter.adapter.odoo.CapabilityResolver}
 * before falling back to the default registry.
 *
 * <p>The tenant is always derived from {@link TenantContext} (the X-Company-Id header).
 * Callers cannot modify another tenant's mappings.
 */
@RestController
@RequestMapping("/api/erp/mappings")
@Tag(name = "ERP Mappings", description = "Manage customer-specific ERP capability overrides")
@RequiredArgsConstructor
@Slf4j
public class ErpMappingController {

    private final ErpMappingService erpMappingService;

    @GetMapping
    @Operation(summary = "List all capability mappings for the current tenant")
    public ResponseEntity<List<ErpMapping>> list() {
        UUID tenantId = requireTenant();
        return ResponseEntity.ok(erpMappingService.findAllByTenant(tenantId));
    }

    @GetMapping("/{capability}")
    @Operation(summary = "Get a specific capability mapping")
    public ResponseEntity<ErpMapping> get(@PathVariable String capability) {
        UUID tenantId = requireTenant();
        return erpMappingService.findByTenantAndCapability(tenantId, capability)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create or update a capability mapping for the current tenant")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mapping created or updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<ErpMapping> upsert(@RequestBody ErpMappingRequest request) {
        UUID tenantId = requireTenant();
        if (request.capability() == null || request.capability().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.mappingType() == null || request.mappingType().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.odooName() == null || request.odooName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.targetModel() == null || request.targetModel().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Upsert: delete existing then create. Race-safe: if a concurrent upsert
        // inserts between our delete and create, catch the unique constraint violation
        // and retry as an update.
        try {
            erpMappingService.deleteMapping(tenantId, request.capability());
            ErpMapping saved = erpMappingService.createMapping(
                    tenantId,
                    request.capability().toUpperCase(),
                    request.mappingType().toUpperCase(),
                    request.odooName(),
                    request.targetModel());
            return ResponseEntity.ok(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent upsert won the race — retry as update
            log.debug("ErpMapping upsert race detected, retrying as update: tenant={} capability={}",
                    tenantId, request.capability());
            erpMappingService.deleteMapping(tenantId, request.capability());
            ErpMapping saved = erpMappingService.createMapping(
                    tenantId,
                    request.capability().toUpperCase(),
                    request.mappingType().toUpperCase(),
                    request.odooName(),
                    request.targetModel());
            return ResponseEntity.ok(saved);
        }
    }

    @DeleteMapping("/{capability}")
    @Operation(summary = "Delete a capability mapping")
    public ResponseEntity<Void> delete(@PathVariable String capability) {
        UUID tenantId = requireTenant();
        erpMappingService.deleteMapping(tenantId, capability);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extract tenant from TenantContext. Returns 400 if no tenant is set.
     */
    private UUID requireTenant() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Missing or invalid X-Company-Id header");
        }
        return tenantId;
    }

    public record ErpMappingRequest(
            String capability,
            String mappingType,
            String odooName,
            String targetModel
    ) {}
}
