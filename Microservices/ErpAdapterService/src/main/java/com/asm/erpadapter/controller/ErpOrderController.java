package com.asm.erpadapter.controller;

import com.asm.erpadapter.port.ErpOrderPort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The ERP provider is resolved per-tenant by {@link ErpProviderRouter} (tenant settings + propagated
 * {@code X-Company-Id}), so no {@code erpProvider} param is accepted.
 */
@RestController
@RequestMapping("/api/erp/orders")
@Tag(name = "ERP Orders", description = "Order creation and reference resolution")
@RequiredArgsConstructor
public class ErpOrderController {

    private final ErpProviderRouter router;

    private ErpOrderPort resolve() {
        return router.getOrder();
    }

    @PostMapping("/resolve")
    @Operation(summary = "Resolve ERP order reference to canonical ID")
    public ResponseEntity<Map<String, Object>> resolveOrderId(
            @RequestParam String erpOrderRef) {

        String resolved = resolve().resolveOrderId(erpOrderRef);
        if (resolved == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("erpOrderId", resolved));
    }

    @GetMapping("/{erpOrderId}/reference")
    @Operation(summary = "Get human-readable order reference")
    public ResponseEntity<Map<String, Object>> getOrderReference(
            @PathVariable String erpOrderId) {

        String reference = resolve().getOrderReference(erpOrderId);
        if (reference == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("reference", reference));
    }
}
