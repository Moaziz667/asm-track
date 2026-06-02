package com.asm.delivery.controller;

import com.asm.delivery.dto.request.WarehouseDepotMappingRequest;
import com.asm.delivery.dto.response.WarehouseDepotMappingResponse;
import com.asm.delivery.service.WarehouseDepotMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for ERP-warehouse → ASM-depot mappings. Used so an imported delivery
 * note's source warehouse resolves to a concrete source depot for multi-depot routing.
 */
@RestController
@RequestMapping("/api/admin/erp/warehouse-mappings")
@Tag(name = "Admin ERP Warehouse Mappings", description = "Map ERP warehouses to ASM depots")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminWarehouseMappingController {

    private final WarehouseDepotMappingService service;

    @GetMapping
    @Operation(summary = "List warehouse → depot mappings")
    public ResponseEntity<List<WarehouseDepotMappingResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Create or update a warehouse → depot mapping (upsert by warehouse code)")
    public ResponseEntity<WarehouseDepotMappingResponse> upsert(@Valid @RequestBody WarehouseDepotMappingRequest req) {
        return ResponseEntity.ok(service.upsert(req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a warehouse → depot mapping")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}
