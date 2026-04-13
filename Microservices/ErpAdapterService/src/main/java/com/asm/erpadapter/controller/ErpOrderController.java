package com.asm.erpadapter.controller;


import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for direct ERP order operations.
 *
 * Endpoints: create map-ready order, resolve order ID, get reference.
 */
@RestController
@RequestMapping("/api/erp/orders")
@Tag(name = "ERP Orders", description = "Order creation and reference resolution")
@RequiredArgsConstructor
public class ErpOrderController {

    private final ErpProviderRouter router;


    @PostMapping("/resolve")
    @Operation(summary = "Resolve ERP order reference to canonical ID")
    public ResponseEntity<Map<String, Object>> resolveOrderId(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam String erpOrderRef) {

        String resolved = router.getOrder(erpProvider).resolveOrderId(erpOrderRef);
        if (resolved == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("erpOrderId", resolved));
    }

    @GetMapping("/{erpOrderId}/reference")
    @Operation(summary = "Get human-readable order reference")
    public ResponseEntity<Map<String, Object>> getOrderReference(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @PathVariable String erpOrderId) {

        String reference = router.getOrder(erpProvider).getOrderReference(erpOrderId);
        if (reference == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("reference", reference));
    }
}
