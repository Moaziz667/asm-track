package com.asm.delivery.controller;

import com.asm.delivery.entity.CashRemittance;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.CashRemittanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The depot's side of the cash chain: counting what a driver hands over, and settling what does not
 * add up.
 *
 * <p>Deliberately not on {@code AdminDeliveryController}: these two calls are the only place in the
 * platform where an operator's identity is part of the business rule rather than just an audit
 * field, and keeping them together makes that visible.
 */
@RestController
@RequestMapping("/api/v1/admin/cash")
@Tag(name = "Cash (COD)", description = "Driver cash handovers and reconciliation")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminCashController {

    private final CashRemittanceService remittanceService;

    @Data
    public static class ReceiveRequest {
        /** What was actually counted at the depot. */
        private BigDecimal receivedTotal;
        private String note;
    }

    @Data
    public static class ReconcileRequest {
        /** Mandatory: a discrepancy closed without an explanation is a discrepancy hidden. */
        private String note;
    }

    @PostMapping("/remittances/{id}/receive")
    @Operation(summary = "Count a driver's handover",
            description = "Records what the depot counted and derives the discrepancy against what the "
                    + "platform knows the driver took. Rejected when the caller is the driver or the "
                    + "person who declared it — the count only means something if a second person does it.")
    public ResponseEntity<CashRemittance> receive(
            @PathVariable UUID id,
            @RequestBody ReceiveRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                remittanceService.receive(id, req.getReceivedTotal(), req.getNote(), principal));
    }

    @PostMapping("/remittances/{id}/reconcile")
    @Operation(summary = "Settle a handover whose figures disagreed",
            description = "Closes a DISPUTED handover with a written explanation, which is mandatory.")
    public ResponseEntity<CashRemittance> reconcile(
            @PathVariable UUID id,
            @RequestBody ReconcileRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(remittanceService.reconcile(id, req.getNote(), principal));
    }

    @GetMapping("/remittances")
    @Operation(summary = "Handovers needing attention",
            description = "Defaults to DECLARED and DISPUTED — the ones waiting on a human. Pass a status "
                    + "to widen. A desk opening on every handover ever made buries today's two under a "
                    + "year of settled ones.")
    public ResponseEntity<org.springframework.data.domain.Page<CashRemittance>> remittances(
            @RequestParam(required = false) com.asm.delivery.entity.CashRemittanceStatus status,
            @org.springdoc.core.annotations.ParameterObject
            @org.springframework.data.web.PageableDefault(size = 25, sort = "declaredAt")
            org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(remittanceService.list(status, pageable));
    }

    @GetMapping("/outstanding-by-driver")
    @Operation(summary = "Who is holding how much, right now")
    public ResponseEntity<?> outstandingByDriver() {
        return ResponseEntity.ok(remittanceService.outstandingByDriver());
    }

    @GetMapping("/circulation")
    @Operation(summary = "Cash currently held by drivers",
            description = "Everything collected and not yet handed over. No ERP can produce this figure: "
                    + "it describes the state of the field between two accounting entries.")
    public ResponseEntity<Map<String, Object>> circulation() {
        return ResponseEntity.ok(Map.of(
                "amount", remittanceService.cashInCirculation(),
                "currency", "TND"));
    }

    @GetMapping("/remittances/counts")
    @Operation(summary = "How many handovers sit in each state",
            description = "Labels the desk's filter tabs. Returns every state, zeros included, so a "
                    + "tab never renders without a figure.")
    public ResponseEntity<Map<String, Long>> counts() {
        return ResponseEntity.ok(remittanceService.countsByStatus());
    }

    @GetMapping("/remittances/{id}/collections")
    @Operation(summary = "The collections included in one handover",
            description = "Each line carries its delivery-note number and customer: an argument at "
                    + "the counter is about a bon de livraison, not about a UUID.")
    public ResponseEntity<List<com.asm.delivery.dto.response.CashCollectionRow>> collections(
            @PathVariable UUID id) {
        return ResponseEntity.ok(remittanceService.collectionRowsOf(id));
    }
}
