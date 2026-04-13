package com.asm.erpadapter.controller;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.request.SyncFailureRequest;
import com.asm.erpadapter.dto.request.SyncFullDeliveryRequest;
import com.asm.erpadapter.dto.request.SyncPartialDeliveryRequest;
import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for ERP sync operations.
 *
 * Endpoints: order creation, cancellation, full/partial delivery, failure notes.
 * Routes to the correct ERP implementation via erpProvider param.
 */
@RestController
@RequestMapping("/api/erp/sync")
@Tag(name = "ERP Sync", description = "Order lifecycle synchronization")
@RequiredArgsConstructor
public class ErpSyncController {

    private final ErpProviderRouter router;
    @PostMapping("/order-cancellation")
    @Operation(summary = "Cancel ERP order")
    public ResponseEntity<Map<String, Object>> syncOrderCancellation(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @RequestParam String erpOrderId) {

        boolean success = router.getSync(erpProvider).syncOrderCancellation(erpOrderId);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/full-delivery")
    @Operation(summary = "Sync full delivery to ERP")
    public ResponseEntity<Map<String, Object>> syncFullDelivery(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @Valid @RequestBody SyncFullDeliveryRequest request) {

        boolean success = router.getSync(erpProvider)
                .syncFullDelivery(request.getErpOrderId(), request.getBackorderPickingId());
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/partial-delivery")
    @Operation(summary = "Sync partial delivery to ERP")
    public ResponseEntity<ErpPartialDeliveryResultDTO> syncPartialDelivery(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @Valid @RequestBody SyncPartialDeliveryRequest request) {

        ErpPartialDeliveryResultDTO result = router.getSync(erpProvider)
                .syncPartialDelivery(request.getErpOrderId(), request.getItems());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/failure")
    @Operation(summary = "Post failure note on ERP order")
    public ResponseEntity<Map<String, Object>> syncFailure(
            @RequestParam(defaultValue = "odoo") String erpProvider,
            @Valid @RequestBody SyncFailureRequest request) {

        boolean success = router.getSync(erpProvider)
                .syncFailure(request.getErpOrderId(), request.getFailureCode(), request.getComment());
        return ResponseEntity.ok(Map.of("success", success));
    }
}
