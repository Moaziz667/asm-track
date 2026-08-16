package com.asm.erpadapter.controller;

import com.asm.erpadapter.dto.*;
import com.asm.erpadapter.port.ErpLookupPort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The ERP provider is resolved per-tenant by {@link ErpProviderRouter} (from the tenant's settings +
 * the propagated {@code X-Company-Id}), so no {@code erpProvider} request param is accepted — the caller
 * can't (and shouldn't) pick another tenant's ERP.
 */
@RestController
@RequestMapping("/api/v1/erp/lookup")
@Tag(name = "ERP Lookup", description = "Search clients, products, pending orders")
@RequiredArgsConstructor
@Validated
public class ErpLookupController {

    private final ErpProviderRouter router;
    private final com.asm.erpadapter.mapping.type.ConversionTrace trace;

    private ErpLookupPort resolve() {
        return router.getLookup();
    }

    @GetMapping("/clients")
    @Operation(summary = "Search ERP clients/customers")
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        try {
            return ResponseEntity.ok(resolve().searchClients(search, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/products")
    @Operation(summary = "Search ERP products")
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        try {
            return ResponseEntity.ok(resolve().searchProducts(search, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/pending-orders")
    @Operation(summary = "List pending ERP orders")
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit) {
        try {
            return ResponseEntity.ok(resolve().getPendingOrders(limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/pending-orders/preview")
    @Operation(summary = "Preview a pending ERP order")
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @RequestParam String erpOrderId) {
        // Collection is switched on only here. The preview is the one caller that needs to explain a
        // blank cell; the import wants the value and nothing else, and pays nothing for this.
        trace.start();
        try {
            ErpPendingOrderPreviewDTO preview = resolve().getPendingOrderPreview(erpOrderId);
            if (preview == null) return ResponseEntity.notFound().build();
            preview.setFieldReads(notableReads(trace.stop()));
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } finally {
            // stop() is idempotent; this clears the thread-local on the error paths above.
            trace.stop();
        }
    }

    /**
     * Keep only what the integrator has to act on.
     *
     * <p>A field that read cleanly needs no annotation — the value speaks for itself, and marking all
     * thirty would drown the two that matter.
     */
    private static java.util.Map<String, ErpPendingOrderPreviewDTO.FieldRead> notableReads(
            java.util.Map<String, com.asm.erpadapter.mapping.type.ConversionOutcome> collected) {
        java.util.Map<String, ErpPendingOrderPreviewDTO.FieldRead> out = new java.util.LinkedHashMap<>();
        collected.forEach((field, outcome) -> {
            if (outcome.isValue()) return;
            out.put(field, new ErpPendingOrderPreviewDTO.FieldRead(
                    outcome.state().name(), outcome.reason()));
        });
        return out;
    }

    @GetMapping("/warehouses")
    @Operation(summary = "List ERP warehouses (source depots)")
    public ResponseEntity<List<ErpWarehouseDTO>> getWarehouses() {
        try {
            return ResponseEntity.ok(resolve().getWarehouses());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/picking-ref")
    @Operation(summary = "Resolve a picking reference/name from its ERP id")
    public ResponseEntity<java.util.Map<String, String>> getPickingRef(
            @RequestParam String pickingId) {
        try {
            String ref = resolve().getPickingRef(pickingId);
            if (ref == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(java.util.Map.of("ref", ref));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/company")
    @Operation(summary = "Get the tenant's own selling company (Odoo res.company)")
    public ResponseEntity<com.asm.erpadapter.dto.ErpCompanyDTO> getCompany() {
        try {
            com.asm.erpadapter.dto.ErpCompanyDTO company = resolve().getCompany();
            if (company == null) return ResponseEntity.noContent().build();
            return ResponseEntity.ok(company);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.noContent().build();
        }
    }
}
