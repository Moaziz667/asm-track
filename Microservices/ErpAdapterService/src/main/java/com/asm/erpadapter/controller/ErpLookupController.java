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
import java.util.UUID;

@RestController
@RequestMapping("/api/erp/lookup")
@Tag(name = "ERP Lookup", description = "Search clients, products, pending orders")
@RequiredArgsConstructor
@Validated
public class ErpLookupController {

    private final ErpProviderRouter     router;
    private ErpLookupPort resolve(String erpProvider) {
        return router.getLookup(erpProvider);
    }

    @GetMapping("/clients")
    @Operation(summary = "Search ERP clients/customers")
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        try {
            return ResponseEntity.ok(resolve(erpProvider).searchClients(search, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/products")
    @Operation(summary = "Search ERP products")
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        try {
            return ResponseEntity.ok(resolve(erpProvider).searchProducts(search, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/pending-orders")
    @Operation(summary = "List pending ERP orders")
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit) {
        try {
            return ResponseEntity.ok(resolve(erpProvider).getPendingOrders(limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/pending-orders/preview")
    @Operation(summary = "Preview a pending ERP order")
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam String erpOrderId) {
        try {
            ErpPendingOrderPreviewDTO preview = resolve(erpProvider)
                    .getPendingOrderPreview(erpOrderId);
            if (preview == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/warehouses")
    @Operation(summary = "List ERP warehouses (source depots)")
    public ResponseEntity<List<ErpWarehouseDTO>> getWarehouses(
            @RequestParam(defaultValue = "odoo") String erpProvider) {
        try {
            return ResponseEntity.ok(resolve(erpProvider).getWarehouses());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/picking-ref")
    @Operation(summary = "Resolve a picking reference/name from its ERP id")
    public ResponseEntity<java.util.Map<String, String>> getPickingRef(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam String pickingId) {
        try {
            String ref = resolve(erpProvider).getPickingRef(pickingId);
            if (ref == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(java.util.Map.of("ref", ref));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping(value = "/delivery-note-pdf", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> deliveryNotePdf(
            @RequestParam(defaultValue="odoo") String erpProvider,
            @RequestParam String blNumber) {
        try {
            byte[] pdf = resolve(erpProvider).getDeliveryNotePdf(blNumber);
            if (pdf == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
