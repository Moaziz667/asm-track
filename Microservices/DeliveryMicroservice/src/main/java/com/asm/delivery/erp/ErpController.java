package com.asm.delivery.erp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/erp")
@Tag(name = "Admin ERP", description = "ERP lookups for clients and products")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
@Validated
public class ErpController {

    private final ErpLookupService erpLookupService;

    @GetMapping("/clients")
    @Operation(summary = "Search ERP clients")
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        return ResponseEntity.ok(erpLookupService.searchClients(search, limit));
    }

    @GetMapping("/products")
    @Operation(summary = "Search ERP products")
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        return ResponseEntity.ok(erpLookupService.searchProducts(search, limit));
    }

    @GetMapping("/pending-orders")
    @Operation(summary = "List pending ERP orders available for import")
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit
    ) {
        return ResponseEntity.ok(erpLookupService.getPendingOrders(limit));
    }

    @GetMapping("/pending-orders/{erpOrderId}")
    @Operation(summary = "Preview a pending ERP order")
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @PathVariable @Size(min = 1, max = 100) String erpOrderId
    ) {
        return ResponseEntity.ok(erpLookupService.getPendingOrderPreview(erpOrderId));
    }

    @PostMapping("/import-order/{erpOrderId}")
    @Operation(summary = "Import a pending ERP order into deliveries")
    public ResponseEntity<com.asm.delivery.dto.response.OrderResponse> importOrder(
            @PathVariable @Size(min = 1, max = 100) String erpOrderId
    ) {
        return ResponseEntity.ok(erpLookupService.importPendingOrder(erpOrderId));
    }

    @PostMapping("/map-ready-order")
    @Operation(summary = "Create a map-ready Odoo order with mappable address coordinates")
    public ResponseEntity<ErpMapOrderResponse> createMapReadyOrder(
            @Valid @RequestBody CreateErpMapOrderRequest request
    ) {
        return ResponseEntity.ok(erpLookupService.createMapReadyOrder(request));
    }
}
