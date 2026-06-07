package com.asm.delivery.controller;

import com.asm.delivery.dto.response.DepotResponse;
import com.asm.delivery.service.DepotService;
import com.asm.delivery.service.DepotSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only depot access plus an ERP sync trigger. Depots mirror the ERP's warehouses
 * (Odoo {@code stock.warehouse}) and are populated by {@link DepotSyncService}, not created by hand.
 */
@RestController
@RequestMapping("/api/v1/depots")
@Tag(name = "Depots", description = "ERP-sourced depots/warehouses (read-only + sync)")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DepotController {

    private final DepotService depotService;
    private final DepotSyncService depotSyncService;

    @GetMapping
    @Operation(summary = "List all depots")
    public ResponseEntity<List<DepotResponse>> list() {
        return ResponseEntity.ok(depotService.list());
    }

    @GetMapping("/active")
    @Operation(summary = "List active depots")
    public ResponseEntity<List<DepotResponse>> listActive() {
        return ResponseEntity.ok(depotService.listActive());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get depot by ID")
    public ResponseEntity<DepotResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(depotService.get(id));
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync depots from the ERP warehouses (Odoo stock.warehouse)")
    public ResponseEntity<DepotSyncService.SyncResult> sync() {
        return ResponseEntity.ok(depotSyncService.syncFromErp());
    }

    @PostMapping("/{id}/geolocate")
    @Operation(summary = "Geocode depot address", description = "Calls Nominatim to resolve GPS coordinates from the depot's stored address and persists the result.")
    public ResponseEntity<DepotResponse> geolocate(
            @Parameter(description = "Depot UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(depotService.geolocate(id));
    }
}
