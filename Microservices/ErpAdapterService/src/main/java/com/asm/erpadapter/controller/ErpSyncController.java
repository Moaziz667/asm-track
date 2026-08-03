package com.asm.erpadapter.controller;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.request.SyncFailureRequest;
import com.asm.erpadapter.dto.request.SyncFullDeliveryRequest;
import com.asm.erpadapter.dto.request.SyncPartialDeliveryRequest;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The ERP provider is resolved per-tenant by {@link ErpProviderRouter} (tenant settings + propagated
 * {@code X-Company-Id}), so no {@code erpProvider} param is accepted — the caller can't override it.
 */
@RestController
@RequestMapping("/api/erp/sync")
@Tag(name = "ERP Sync", description = "Order lifecycle synchronization")
@RequiredArgsConstructor
public class ErpSyncController {

    private final ErpProviderRouter router;

    private ErpSyncPort resolve() {
        return router.getSync();
    }

    @PostMapping("/order-cancellation")
    @Operation(summary = "Cancel ERP order")
    public ResponseEntity<Map<String, Object>> syncOrderCancellation(
            @RequestParam String erpOrderId,
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String pickingRef) {

        boolean success = resolve().syncOrderCancellation(erpOrderId, transactionId, pickingRef);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/invoice")
    @Operation(summary = "Create + validate an ERP invoice for a delivered order (synchronous, admin-triggered)")
    public ResponseEntity<Map<String, Object>> createInvoice(
            @RequestParam String erpOrderId,
            @RequestParam(required = false) String pickingRef) {

        String invoiceRef = resolve().createInvoice(erpOrderId, pickingRef);
        if (invoiceRef == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Invoice could not be created — create it manually in the ERP."));
        }
        return ResponseEntity.ok(Map.of("success", true, "invoiceRef", invoiceRef));
    }

    @GetMapping("/invoice-pdf")
    @Operation(summary = "Download the rendered PDF of an ERP invoice")
    public ResponseEntity<byte[]> invoicePdf(
            @RequestParam String invoiceRef) {

        byte[] pdf = resolve().getInvoicePdf(invoiceRef);
        if (pdf == null || pdf.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoiceRef + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/delivery-note-pdf")
    @Operation(summary = "Download the delivery note (bon de livraison) as the ERP renders it")
    public ResponseEntity<byte[]> deliveryNotePdf(
            @RequestParam String pickingRef) {

        byte[] pdf = resolve().getDeliveryNotePdf(pickingRef);
        if (pdf == null || pdf.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + pickingRef.replace('/', '-') + ".pdf\"")
                .body(pdf);
    }

    @PostMapping("/full-delivery")
    @Operation(summary = "Sync full delivery to ERP")
    public ResponseEntity<Map<String, Object>> syncFullDelivery(
            @Valid @RequestBody SyncFullDeliveryRequest request) {

        boolean success = resolve()
                .syncFullDelivery(request.getErpOrderId(), request.getBackorderPickingId(), request.getTransactionId(), request.getPickingRef());
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/partial-delivery")
    @Operation(summary = "Sync partial delivery to ERP")
    public ResponseEntity<ErpPartialDeliveryResultDTO> syncPartialDelivery(
            @Valid @RequestBody SyncPartialDeliveryRequest request) {

        ErpPartialDeliveryResultDTO result = resolve()
                .syncPartialDelivery(request.getErpOrderId(), request.getItems(), request.getTransactionId(), request.getPickingRef());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/failure")
    @Operation(summary = "Post failure note on ERP order")
    public ResponseEntity<Map<String, Object>> syncFailure(
            @Valid @RequestBody SyncFailureRequest request) {

        boolean success = resolve()
                .syncFailure(request.getErpOrderId(), request.getFailureCode(), request.getComment(), request.getTransactionId(), request.getPickingRef());
        return ResponseEntity.ok(Map.of("success", success));
    }
}
