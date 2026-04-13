package com.asm.erpadapter.controller;

import com.asm.erpadapter.dto.ErpClientDTO;
import com.asm.erpadapter.dto.ErpPendingOrderPreviewDTO;
import com.asm.erpadapter.dto.ErpPendingOrderSummaryDTO;
import com.asm.erpadapter.dto.ErpProductDTO;
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
 * REST controller for ERP lookup operations.
 *
 * Endpoints: search clients, search products, list/preview pending orders.
 * All data is read-only from the ERP — no mutations.
 */
@RestController
@RequestMapping("/api/erp/lookup")
@Tag(name = "ERP Lookup", description = "Search clients, products, pending orders")
@RequiredArgsConstructor
@Validated
public class ErpLookupController {

    private final ErpProviderRouter router;

    @GetMapping("/clients")
    @Operation(summary = "Search ERP clients/customers")
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(router.getLookup(erpProvider).searchClients(search, limit));
    }

    @GetMapping("/products")
    @Operation(summary = "Search ERP products")
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(router.getLookup(erpProvider).searchProducts(search, limit));
    }

    @GetMapping("/pending-orders")
    @Operation(summary = "List pending ERP orders")
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit) {
        return ResponseEntity.ok(router.getLookup(erpProvider).getPendingOrders(limit));
    }

    @GetMapping("/pending-orders/{erpOrderId}")
    @Operation(summary = "Preview a pending ERP order")
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @PathVariable String erpOrderId) {
        ErpPendingOrderPreviewDTO preview = router.getLookup(erpProvider).getPendingOrderPreview(erpOrderId);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(preview);
    }
}
