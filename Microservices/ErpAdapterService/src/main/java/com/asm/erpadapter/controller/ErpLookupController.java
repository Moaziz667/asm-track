package com.asm.erpadapter.controller;

import com.asm.erpadapter.config.CompanyAdapterFactory;
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
    private final CompanyAdapterFactory factory;

    private ErpLookupPort resolve(String erpProvider, String companyId) {
        return companyId != null
                ? factory.lookupFor(UUID.fromString(companyId))
                : router.getLookup(erpProvider);
    }

    @GetMapping("/clients")
    @Operation(summary = "Search ERP clients/customers")
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestHeader(value = "X-Company-Id", required = false) String companyId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(resolve(erpProvider, companyId).searchClients(search, limit));
    }

    @GetMapping("/products")
    @Operation(summary = "Search ERP products")
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestHeader(value = "X-Company-Id", required = false) String companyId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(resolve(erpProvider, companyId).searchProducts(search, limit));
    }

    @GetMapping("/pending-orders")
    @Operation(summary = "List pending ERP orders")
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestHeader(value = "X-Company-Id", required = false) String companyId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit) {
        return ResponseEntity.ok(resolve(erpProvider, companyId).getPendingOrders(limit));
    }

    @GetMapping("/pending-orders/{erpOrderId}")
    @Operation(summary = "Preview a pending ERP order")
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestHeader(value = "X-Company-Id", required = false) String companyId,
            @PathVariable String erpOrderId) {
        ErpPendingOrderPreviewDTO preview = resolve(erpProvider, companyId)
                .getPendingOrderPreview(erpOrderId);
        if (preview == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(preview);
    }
}
